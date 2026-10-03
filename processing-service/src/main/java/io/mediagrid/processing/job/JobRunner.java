package io.mediagrid.processing.job;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.mediagrid.processing.config.ProcessingProperties;
import io.mediagrid.processing.media.MediaProcessor;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.FileSystemUtils;

/**
 * Исполнители: берут задачи из очереди в базе и обрабатывают их в отдельных потоках, не больше
 * mediagrid.processing.workers одновременно. Задача берётся по расписанию и сразу при поступлении события.
 * <p>
 * Пока цепь к службе хранения разомкнута (она недоступна), новые задачи не берутся: попытки не тратятся
 * впустую, а задачи дожидаются в очереди.
 */
@Component
public class JobRunner {

    private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

    private final JobService jobs;
    private final MediaProcessor processor;
    private final JobNotifier notifier;
    private final CircuitBreaker storageBreaker;
    private final ProcessingProperties properties;
    private final ExecutorService executor;
    private final Semaphore freeWorkers;
    private final Map<UUID, JobContext> active = new ConcurrentHashMap<>();
    private volatile boolean stopping;

    public JobRunner(JobService jobs, MediaProcessor processor, JobNotifier notifier,
                     @Qualifier("storageBreaker") CircuitBreaker storageBreaker, ProcessingProperties properties) {
        this.jobs = jobs;
        this.processor = processor;
        this.notifier = notifier;
        this.storageBreaker = storageBreaker;
        this.properties = properties;
        this.executor = Executors.newFixedThreadPool(properties.workers(),
                Thread.ofPlatform().name("processing-", 1).factory());
        this.freeWorkers = new Semaphore(properties.workers());
    }

    /**
     * Остатки временных файлов от прошлого запуска (служба могла упасть посреди работы). Удаляется только
     * содержимое: сам каталог в контейнере — точка подключения тома, её удалить нельзя.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void cleanWorkDir() throws IOException {
        Files.createDirectories(properties.workDir());
        try (Stream<Path> leftovers = Files.list(properties.workDir())) {
            for (Path leftover : leftovers.toList()) {
                FileSystemUtils.deleteRecursively(leftover);
            }
        }
    }

    /** Раздать задачи свободным исполнителям. */
    @Scheduled(fixedDelayString = "${mediagrid.processing.poll-interval:5s}", initialDelayString = "PT5S")
    public synchronized void dispatch() {
        while (!stopping && storageBreaker.getState() != CircuitBreaker.State.OPEN && freeWorkers.tryAcquire()) {
            Optional<JobTicket> next;
            try {
                next = jobs.claimNext();
            } catch (RuntimeException e) {
                freeWorkers.release();
                log.warn("Не удалось взять задачу из очереди: {}", e.toString());
                return;
            }
            if (next.isEmpty()) {
                freeWorkers.release();
                return;
            }
            JobTicket ticket = next.get();
            JobContext context = new JobContext(ticket.mediaId(), jobs::progress);
            active.put(ticket.mediaId(), context);
            executor.execute(() -> run(ticket, context));
        }
    }

    private void run(JobTicket ticket, JobContext context) {
        UUID id = ticket.mediaId();
        log.info("Обработка файла {} ({}), попытка {}", id, ticket.contentType(), ticket.attempt());
        try {
            ProcessingResult result = processor.process(ticket, context);
            if (!context.isCancelled()) {
                jobs.succeed(id, result);
            }
        } catch (RuntimeException e) {
            settle(id, context, e);
        } finally {
            active.remove(id);
            freeWorkers.release();
        }
        notifier.sendPending();
        dispatch();
    }

    /** Исход неудачной попытки зависит от вида сбоя; остановленная снаружи задача не трогается. */
    private void settle(UUID id, JobContext context, RuntimeException error) {
        if (context.isCancelled()) {
            log.info("Обработка файла {} остановлена", id);
            return;
        }
        switch (error) {
            case JobFailure.Transient e -> jobs.retryOrFail(id, e.getMessage());
            case JobFailure.Permanent e -> jobs.fail(id, e.getMessage());
            case JobFailure.Gone e -> jobs.remove(id);
            default -> {
                log.error("Сбой при обработке файла {}", id, error);
                jobs.fail(id, "Внутренняя ошибка службы обработки: " + error);
            }
        }
    }

    /** Остановить обработку файла, запись о котором удалена. */
    public void cancel(UUID mediaId) {
        JobContext context = active.get(mediaId);
        if (context != null) {
            context.cancel();
        }
    }

    /** Отметки исполнителей. Если задача больше не выполняется (её удалили или вернули в очередь) — стоп. */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void heartbeat() {
        active.forEach((id, context) -> {
            if (!jobs.heartbeat(id)) {
                context.cancel();
            }
        });
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void recoverStale() {
        jobs.recoverStale();
    }

    /** При выключении службы начатые задачи возвращаются в очередь, а ffmpeg останавливается. */
    @PreDestroy
    public void stop() {
        stopping = true;
        active.forEach((id, context) -> {
            context.cancel();
            try {
                jobs.release(id);
            } catch (RuntimeException e) {
                log.warn("Не удалось вернуть задачу {} в очередь: {}", id, e.toString());
            }
        });
        executor.shutdownNow();
    }
}
