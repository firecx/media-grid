package io.mediagrid.auth.token;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Отозванные токены доступа: одного входа (sessionId) или все токены пользователя поколения меньше
 * minVersion. Хранится, пока отозванные токены ещё могут действовать.
 */
@Entity
@Table(name = "access_revocations")
public class AccessRevocation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID userId;

    private UUID sessionId;

    private Integer minVersion;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected AccessRevocation() {
    }

    private AccessRevocation(UUID userId, UUID sessionId, Integer minVersion, Instant expiresAt) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.minVersion = minVersion;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    static AccessRevocation session(UUID userId, UUID sessionId, Instant expiresAt) {
        return new AccessRevocation(userId, sessionId, null, expiresAt);
    }

    static AccessRevocation olderThan(UUID userId, int minVersion, Instant expiresAt) {
        return new AccessRevocation(userId, null, minVersion, expiresAt);
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public Integer getMinVersion() {
        return minVersion;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
