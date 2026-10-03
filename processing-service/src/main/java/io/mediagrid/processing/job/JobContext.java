package io.mediagrid.processing.job;

import java.time.Duration;
import java.util.UUID;

/**
 * Выполняемая задача глазами исполнителя: сообщает о ходе работы и позволяет остановить её снаружи
 * (запись удалена, служба выключается) — вместе с запущенным процессом ffmpeg.
 */
public final class JobContext {

    /** Ход работы пишется в базу не чаще этого, кроме смены этапа. */
    static final Duration REPORT_INTERVAL = Duration.ofSeconds(2);

    /** Куда сообщать о ходе работы; false — задачи больше нет, пора остановиться. */
    @FunctionalInterface
    public interface ProgressSink {
        boolean report(UUID mediaId, JobStage stage, int percent);
    }

    private final UUID mediaId;
    private final ProgressSink sink;
    private volatile boolean cancelled;
    private Process process;
    private JobStage reportedStage;
    private long reportedAt;

    public JobContext(UUID mediaId, ProgressSink sink) {
        this.mediaId = mediaId;
        this.sink = sink;
    }

    public UUID mediaId() {
        return mediaId;
    }

    /** Этап и процент готовности задачи целиком (0–100). */
    public void report(JobStage stage, int percent) {
        long now = System.nanoTime();
        if (stage == reportedStage && now - reportedAt < REPORT_INTERVAL.toNanos()) {
            return;
        }
        reportedStage = stage;
        reportedAt = now;
        if (!sink.report(mediaId, stage, percent)) {
            cancel();
        }
        checkCancelled();
    }

    /** Запущенный процесс: при остановке задачи он будет завершён. */
    public synchronized void attach(Process started) {
        process = started;
        if (cancelled) {
            started.destroyForcibly();
        }
    }

    public synchronized void detach() {
        process = null;
    }

    /** Остановить задачу. Состояние в базе исполнитель после этого не меняет. */
    public synchronized void cancel() {
        cancelled = true;
        if (process != null) {
            process.destroyForcibly();
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void checkCancelled() {
        if (cancelled) {
            throw new JobFailure.Gone("Обработка остановлена");
        }
    }
}
