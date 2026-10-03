package io.mediagrid.storage.file;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** Производный файл. Новая версия того же вида заменяет прежнюю (повторная обработка). */
@Entity
@Table(name = "file_variants")
@IdClass(FileVariant.Key.class)
public class FileVariant {

    @Id
    private UUID mediaId;

    @Id
    @Enumerated(EnumType.STRING)
    private VariantKind kind;

    @Column(nullable = false)
    private String contentType;

    private long sizeBytes;

    @Column(nullable = false)
    private String storageKey;

    @Column(nullable = false)
    private Instant createdAt;

    protected FileVariant() {
    }

    public FileVariant(UUID mediaId, VariantKind kind, String contentType, long sizeBytes, String storageKey) {
        this.mediaId = mediaId;
        this.kind = kind;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.storageKey = storageKey;
        this.createdAt = Instant.now();
    }

    public UUID getMediaId() {
        return mediaId;
    }

    public VariantKind getKind() {
        return kind;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public record Key(UUID mediaId, VariantKind kind) implements Serializable {
    }
}
