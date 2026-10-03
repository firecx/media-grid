package io.mediagrid.processing.job;

/**
 * Почему задача не выполнена. От вида зависит, что с ней будет дальше: временный сбой — повтор позже,
 * ошибка обработки — FAILED сразу, файл удалён — задача снимается без сообщения.
 */
public abstract sealed class JobFailure extends RuntimeException
        permits JobFailure.Transient, JobFailure.Permanent, JobFailure.Gone {

    protected JobFailure(String message, Throwable cause) {
        super(message, cause);
    }

    /** Временный сбой (служба хранения или авторизации недоступна): задача повторится с паузой. */
    public static final class Transient extends JobFailure {
        public Transient(String message, Throwable cause) {
            super(message, cause);
        }

        public Transient(String message) {
            this(message, null);
        }
    }

    /** Файл не удаётся обработать (повреждён, неизвестный формат): повтор не поможет. */
    public static final class Permanent extends JobFailure {
        public Permanent(String message) {
            super(message, null);
        }
    }

    /** Запись удалена, пока шла обработка: результат никому не нужен. */
    public static final class Gone extends JobFailure {
        public Gone(String message) {
            super(message, null);
        }
    }
}
