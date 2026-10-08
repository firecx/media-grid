package io.mediagrid.processing.job;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

/** Задача обработки одного файла и её результат. Одна задача на запись каталога. */
@Entity
@Table(name = "jobs")
public class Job implements Persistable<UUID> {

    static final int ERROR_MAX_LENGTH = 1000;

    @Id
    private UUID mediaId;

    @Column(nullable = false, updatable = false)
    private UUID ownerId;

    @Column(nullable = false, updatable = false)
    private String contentType;

    @Column(nullable = false, updatable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Enumerated(EnumType.STRING)
    private JobStage stage;

    private int progress;

    private int attempts;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    private String error;

    private Long durationMs;
    private Integer width;
    private Integer height;
    private String videoCodec;
    private String audioCodec;
    private boolean transcoded;
    private boolean hasPreview;

    private boolean notified;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant startedAt;
    private Instant finishedAt;
    private Instant heartbeatAt;

    /** Идентификатор задаётся снаружи, поэтому новизну записи определяет не он, а этот признак. */
    @Transient
    private boolean isNew;

    protected Job() {
    }

    /** Трасса запроса, в котором загрузили файл (W3C traceparent); null — без трассы. */
    @Column(updatable = false)
    private String traceParent;

    public Job(UUID mediaId, UUID ownerId, String contentType, long sizeBytes, String traceParent) {
        this.traceParent = traceParent;
        this.mediaId = mediaId;
        this.ownerId = ownerId;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.status = JobStatus.QUEUED;
        this.createdAt = Instant.now();
        this.nextAttemptAt = createdAt;
        this.isNew = true;
    }

    @PostPersist
    @PostLoad
    void markStored() {
        isNew = false;
    }

    /** Исполнитель взял задачу. */
    void start() {
        status = JobStatus.RUNNING;
        attempts++;
        stage = JobStage.DOWNLOADING;
        progress = 0;
        error = null;
        startedAt = Instant.now();
        heartbeatAt = startedAt;
    }

    void succeed(ProcessingResult result) {
        durationMs = result.durationMs();
        width = result.width();
        height = result.height();
        videoCodec = result.videoCodec();
        audioCodec = result.audioCodec();
        transcoded = result.transcoded();
        hasPreview = result.hasPreview();
        finish(JobStatus.DONE, null);
        progress = 100;
    }

    void fail(String reason) {
        finish(JobStatus.FAILED, reason);
    }

    /** Временный сбой: задача вернётся в очередь не раньше чем через delay. */
    void retryLater(Duration delay, String reason) {
        status = JobStatus.QUEUED;
        stage = null;
        progress = 0;
        error = truncate(reason);
        heartbeatAt = null;
        nextAttemptAt = Instant.now().plus(delay);
    }

    /** Снова в очередь сразу: исполнитель остановлен или администратор попросил обработать заново. */
    void requeue(boolean resetAttempts) {
        retryLater(Duration.ZERO, null);
        if (resetAttempts) {
            attempts = 0;
        }
        finishedAt = null;
        notified = false;
    }

    /** Результат нужно отправить ещё раз (каталог мог его не получить). */
    void resend() {
        notified = false;
    }

    void markNotified() {
        notified = true;
    }

    private void finish(JobStatus finalStatus, String reason) {
        status = finalStatus;
        stage = null;
        error = truncate(reason);
        finishedAt = Instant.now();
        heartbeatAt = null;
        notified = false;
    }

    private static String truncate(String value) {
        return value == null || value.length() <= ERROR_MAX_LENGTH ? value : value.substring(0, ERROR_MAX_LENGTH);
    }

    @Override
    public UUID getId() {
        return mediaId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public UUID getMediaId() {
        return mediaId;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public JobStatus getStatus() {
        return status;
    }

    public JobStage getStage() {
        return stage;
    }

    public int getProgress() {
        return progress;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getError() {
        return error;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public Integer getWidth() {
        return width;
    }

    public Integer getHeight() {
        return height;
    }

    public String getVideoCodec() {
        return videoCodec;
    }

    public String getAudioCodec() {
        return audioCodec;
    }

    public boolean isTranscoded() {
        return transcoded;
    }

    public boolean isHasPreview() {
        return hasPreview;
    }

    public boolean isNotified() {
        return notified;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getTraceParent() {
        return traceParent;
    }
}
