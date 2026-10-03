package io.mediagrid.processing.job;

/** Состояние задачи обработки. */
public enum JobStatus {
    /** Ждёт исполнителя (в том числе повтора после временного сбоя). */
    QUEUED,
    /** Обрабатывается. */
    RUNNING,
    /** Готово; производные файлы сохранены в службе хранения. */
    DONE,
    /** Обработать не удалось; исходный файл остаётся доступным. */
    FAILED;

    public boolean isFinished() {
        return this == DONE || this == FAILED;
    }
}
