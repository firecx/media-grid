package io.mediagrid.processing.web;

import java.time.Instant;
import java.util.UUID;

import io.mediagrid.processing.job.Job;
import io.mediagrid.processing.job.JobStage;
import io.mediagrid.processing.job.JobStatus;

/** Ход и итог обработки файла вместе с метаданными обработки. */
public record JobResponse(UUID mediaId, JobStatus status, JobStage stage, int progress, int attempts,
                          Instant nextAttemptAt, String error, Long durationMs, Integer width, Integer height,
                          String videoCodec, String audioCodec, boolean transcoded, boolean hasPreview,
                          Instant createdAt, Instant startedAt, Instant finishedAt) {

    static JobResponse of(Job job) {
        return new JobResponse(job.getMediaId(), job.getStatus(), job.getStage(), job.getProgress(),
                job.getAttempts(), job.getStatus() == JobStatus.QUEUED ? job.getNextAttemptAt() : null,
                job.getError(), job.getDurationMs(), job.getWidth(), job.getHeight(), job.getVideoCodec(),
                job.getAudioCodec(), job.isTranscoded(), job.isHasPreview(), job.getCreatedAt(),
                job.getStartedAt(), job.getFinishedAt());
    }
}
