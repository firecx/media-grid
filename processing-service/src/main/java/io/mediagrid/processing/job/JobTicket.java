package io.mediagrid.processing.job;

import java.util.UUID;

/** Что исполнитель знает о взятой задаче; снимок, не связанный с транзакцией. */
public record JobTicket(UUID mediaId, String contentType, long sizeBytes, int attempt) {

    static JobTicket of(Job job) {
        return new JobTicket(job.getMediaId(), job.getContentType(), job.getSizeBytes(), job.getAttempts());
    }
}
