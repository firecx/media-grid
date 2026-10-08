package io.mediagrid.processing.job;

import java.util.UUID;

/**
 * Что исполнитель знает о взятой задаче; снимок, не связанный с транзакцией.
 *
 * @param traceParent трасса запроса, в котором загрузили файл (W3C traceparent), или null
 */
public record JobTicket(UUID mediaId, String contentType, long sizeBytes, int attempt, String traceParent) {

    static JobTicket of(Job job) {
        return new JobTicket(job.getMediaId(), job.getContentType(), job.getSizeBytes(), job.getAttempts(),
                job.getTraceParent());
    }
}
