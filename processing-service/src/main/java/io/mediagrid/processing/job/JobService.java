package io.mediagrid.processing.job;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.mediagrid.common.events.FileUploadedEvent;
import io.mediagrid.processing.config.ProcessingProperties;
import io.mediagrid.support.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Очередь задач обработки в базе: постановка, выдача исполнителю, итоги, повторы. */
@Service
public class JobService {

    /** Пауза перед повтором растёт вдвое, но не дольше этого. */
    static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(30);

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository jobs;
    private final ProcessingProperties properties;
    private final JobTracing tracing;

    public JobService(JobRepository jobs, ProcessingProperties properties, JobTracing tracing) {
        this.jobs = jobs;
        this.properties = properties;
        this.tracing = tracing;
    }

    /**
     * Файл загружен — задача в очередь. Повторное событие задачу не дублирует; если файл уже обработан,
     * результат отправляется ещё раз: служба хранения повторяет событие, когда каталог мог его потерять.
     *
     * @return true, если появилась новая задача
     */
    @Transactional
    public boolean enqueue(FileUploadedEvent event) {
        Optional<Job> existing = jobs.findById(event.mediaId());
        if (existing.isPresent()) {
            Job job = existing.get();
            if (job.getStatus().isFinished()) {
                job.resend();
            }
            return false;
        }
        jobs.save(new Job(event.mediaId(), event.ownerId(), event.contentType(), event.sizeBytes(),
                tracing.currentTraceParent()));
        log.info("Файл {} ({}, {} байт) поставлен в очередь обработки", event.mediaId(), event.contentType(),
                event.sizeBytes());
        return true;
    }

    /** Следующая задача для исполнителя; она сразу помечается как выполняемая. */
    @Transactional
    public Optional<JobTicket> claimNext() {
        return jobs.lockNextQueued().map(job -> {
            job.start();
            return JobTicket.of(job);
        });
    }

    /** @return false, если задачи больше нет (запись удалена) — исполнителю пора остановиться */
    @Transactional
    public boolean progress(UUID mediaId, JobStage stage, int percent) {
        return jobs.updateProgress(mediaId, stage, Math.clamp(percent, 0, 100), Instant.now()) > 0;
    }

    /** @return false, если задачи больше нет или она уже не выполняется */
    @Transactional
    public boolean heartbeat(UUID mediaId) {
        return jobs.heartbeat(mediaId, Instant.now()) > 0;
    }

    @Transactional
    public void succeed(UUID mediaId, ProcessingResult result) {
        running(mediaId).ifPresent(job -> {
            job.succeed(result);
            log.info("Файл {} обработан: перекодирован — {}, превью — {}", mediaId, result.transcoded(),
                    result.hasPreview());
        });
    }

    @Transactional
    public void fail(UUID mediaId, String reason) {
        running(mediaId).ifPresent(job -> {
            job.fail(reason);
            log.warn("Файл {} обработать не удалось: {}", mediaId, reason);
        });
    }

    /** Временный сбой: повтор с нарастающей паузой, а когда попытки кончились, — FAILED. */
    @Transactional
    public void retryOrFail(UUID mediaId, String reason) {
        running(mediaId).ifPresent(job -> {
            if (job.getAttempts() >= properties.maxAttempts()) {
                job.fail("Не удалось за " + job.getAttempts() + " попыток: " + reason);
                log.warn("Файл {}: попытки исчерпаны: {}", mediaId, reason);
                return;
            }
            Duration delay = retryDelay(job.getAttempts());
            job.retryLater(delay, reason);
            log.warn("Файл {}: временный сбой, повтор через {}: {}", mediaId, delay, reason);
        });
    }

    /** Исполнитель остановлен (служба выключается) — задачу сразу заберёт другой. */
    @Transactional
    public void release(UUID mediaId) {
        running(mediaId).ifPresent(job -> job.requeue(false));
    }

    /** Запись каталога удалена: задача больше не нужна, в каком бы состоянии она ни была. */
    @Transactional
    public void remove(UUID mediaId) {
        jobs.findById(mediaId).ifPresent(job -> {
            jobs.delete(job);
            log.info("Задача обработки файла {} снята: запись удалена", mediaId);
        });
    }

    /**
     * Задачи, исполнитель которых давно не отмечался (служба упала посреди работы), возвращаются
     * в очередь; упавшие слишком много раз — завершаются ошибкой.
     */
    @Transactional
    public int recoverStale() {
        List<Job> stale = jobs.findTop100ByStatusAndHeartbeatAtBefore(JobStatus.RUNNING,
                Instant.now().minus(properties.staleAfter()));
        for (Job job : stale) {
            if (job.getAttempts() >= properties.maxAttempts()) {
                job.fail("Обработка прерывалась " + job.getAttempts() + " раз");
            } else {
                job.requeue(false);
            }
            log.warn("Задача {} без отметки исполнителя дольше {}: {}", job.getMediaId(), properties.staleAfter(),
                    job.getStatus());
        }
        return stale.size();
    }

    /** Обработать заново (администратор): после исправления настроек или обновления ffmpeg. */
    @Transactional
    public Job retry(UUID mediaId) {
        Job job = jobs.findById(mediaId).orElseThrow(() -> ApiException.notFound("Задача не найдена"));
        if (!job.getStatus().isFinished()) {
            throw ApiException.conflict("JOB_ACTIVE", "Задача уже в очереди или выполняется");
        }
        job.requeue(true);
        log.info("Файл {} поставлен на повторную обработку", mediaId);
        return job;
    }

    @Transactional(readOnly = true)
    public Optional<Job> find(UUID mediaId) {
        return jobs.findById(mediaId);
    }

    @Transactional(readOnly = true)
    public Page<Job> list(JobStatus status, Pageable pageable) {
        return status == null ? jobs.findAll(pageable) : jobs.findByStatus(status, pageable);
    }

    @Transactional(readOnly = true)
    public List<Job> unnotified() {
        return jobs.findUnnotified();
    }

    @Transactional
    public void markNotified(UUID mediaId) {
        jobs.findById(mediaId).ifPresent(Job::markNotified);
    }

    Duration retryDelay(int attempt) {
        Duration delay = properties.retryDelay().multipliedBy(1L << Math.min(attempt - 1, 20));
        return delay.compareTo(MAX_RETRY_DELAY) > 0 ? MAX_RETRY_DELAY : delay;
    }

    private Optional<Job> running(UUID mediaId) {
        return jobs.findById(mediaId).filter(job -> job.getStatus() == JobStatus.RUNNING);
    }
}
