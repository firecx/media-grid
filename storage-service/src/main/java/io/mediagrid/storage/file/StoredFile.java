package io.mediagrid.storage.file;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.springframework.data.domain.Persistable;

/** Файл записи каталога: где лежит и сколько получено. Идентификатор — id записи в службе медиаданных. */
@Entity
@Table(name = "stored_files")
public class StoredFile implements Persistable<UUID> {

    @Id
    private UUID mediaId;

    @Column(nullable = false, updatable = false)
    private UUID ownerId;

    @Column(nullable = false, updatable = false)
    private String contentType;

    @Column(nullable = false, updatable = false)
    private String originalFilename;

    @Column(nullable = false, updatable = false)
    private long expectedSize;

    private long receivedSize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FileStatus status;

    @Column(nullable = false, updatable = false)
    private String storageBackend;

    @Column(nullable = false, updatable = false)
    private String storageKey;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant completedAt;

    /** Идентификатор задаётся снаружи, поэтому новизну записи определяет не он, а этот признак. */
    private transient boolean isNew;

    protected StoredFile() {
    }

    public StoredFile(UUID mediaId, UUID ownerId, String contentType, String originalFilename, long expectedSize,
                      String storageBackend, String storageKey) {
        this.mediaId = mediaId;
        this.ownerId = ownerId;
        this.contentType = contentType;
        this.originalFilename = originalFilename;
        this.expectedSize = expectedSize;
        this.status = FileStatus.UPLOADING;
        this.storageBackend = storageBackend;
        this.storageKey = storageKey;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.isNew = true;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @PostPersist
    @PostLoad
    void markStored() {
        isNew = false;
    }

    /** Отмечает, сколько байт уже получено; при получении всего файла загрузка завершается. */
    public void received(long bytes) {
        receivedSize = bytes;
        if (receivedSize == expectedSize && status != FileStatus.COMPLETE) {
            status = FileStatus.COMPLETE;
            completedAt = Instant.now();
        }
    }

    public boolean isComplete() {
        return status == FileStatus.COMPLETE;
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

    public String getOriginalFilename() {
        return originalFilename;
    }

    public long getExpectedSize() {
        return expectedSize;
    }

    public long getReceivedSize() {
        return receivedSize;
    }

    public FileStatus getStatus() {
        return status;
    }

    public String getStorageBackend() {
        return storageBackend;
    }

    public String getStorageKey() {
        return storageKey;
    }
}
