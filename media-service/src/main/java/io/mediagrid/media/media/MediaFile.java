package io.mediagrid.media.media;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import io.mediagrid.media.category.Category;
import io.mediagrid.media.tag.Tag;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** Запись каталога о файле. Сам файл хранит служба хранения под тем же идентификатором. */
@Entity
@Table(name = "media_files")
public class MediaFile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID ownerId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, updatable = false)
    private String originalFilename;

    @Column(nullable = false, updatable = false)
    private String contentType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private MediaKind mediaKind;

    @Column(nullable = false, updatable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MediaStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Visibility visibility;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @ManyToMany
    @JoinTable(name = "media_tags",
            joinColumns = @JoinColumn(name = "media_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<Tag> tags = new LinkedHashSet<>();

    private boolean hasPreview;

    private String processingError;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant uploadedAt;

    protected MediaFile() {
    }

    public MediaFile(UUID ownerId, String title, String originalFilename, String contentType, MediaKind mediaKind,
                     long sizeBytes, Visibility visibility) {
        this.ownerId = ownerId;
        this.title = title;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.mediaKind = mediaKind;
        this.sizeBytes = sizeBytes;
        this.visibility = visibility;
        this.status = MediaStatus.PENDING_UPLOAD;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public void markUploaded() {
        status = MediaStatus.UPLOADED;
        uploadedAt = Instant.now();
    }

    public void markProcessed(boolean success, boolean hasPreview, String error) {
        status = success ? MediaStatus.READY : MediaStatus.FAILED;
        this.hasPreview = hasPreview;
        this.processingError = success ? null : error;
    }

    public boolean isOwnedBy(UUID userId) {
        return ownerId.equals(userId);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public MediaKind getMediaKind() {
        return mediaKind;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public MediaStatus getStatus() {
        return status;
    }

    public Visibility getVisibility() {
        return visibility;
    }

    public void setVisibility(Visibility visibility) {
        this.visibility = visibility;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public Set<Tag> getTags() {
        return tags;
    }

    public void setTags(Set<Tag> tags) {
        this.tags = tags;
    }

    public boolean hasPreview() {
        return hasPreview;
    }

    public String getProcessingError() {
        return processingError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }
}
