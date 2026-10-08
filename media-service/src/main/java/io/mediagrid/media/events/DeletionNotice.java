package io.mediagrid.media.events;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Удаление записи, о котором служба хранения ещё не узнала (событие media.deleted не подтверждено шиной). */
@Entity
@Table(name = "deletion_notices")
public class DeletionNotice {

    @Id
    private UUID mediaId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected DeletionNotice() {
    }

    DeletionNotice(UUID mediaId) {
        this.mediaId = mediaId;
        this.createdAt = Instant.now();
    }

    public UUID getMediaId() {
        return mediaId;
    }
}
