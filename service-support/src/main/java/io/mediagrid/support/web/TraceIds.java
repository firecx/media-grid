package io.mediagrid.support.web;

import org.slf4j.MDC;

/**
 * Номер трассы текущего запроса (сквозная трассировка, ТЗ п. 4.1.1). Micrometer Tracing кладёт его в
 * контекст журнала (MDC) на время обработки запроса; по нему администратор находит запрос в журналах
 * и трассах всех служб. Вне запроса — null.
 */
public final class TraceIds {

    /** Ключ, под которым Micrometer Tracing кладёт номер трассы в MDC. */
    static final String MDC_KEY = "traceId";

    private TraceIds() {
    }

    public static String current() {
        String traceId = MDC.get(MDC_KEY);
        return traceId == null || traceId.isBlank() ? null : traceId;
    }
}
