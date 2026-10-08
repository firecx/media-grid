package io.mediagrid.auth.token;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AccessRevocationRepository extends JpaRepository<AccessRevocation, UUID> {

    List<AccessRevocation> findByExpiresAtAfterOrderByCreatedAt(Instant now);

    /** Отозван ли токен входа sessionId поколения version. sessionId может быть null (токен без входа). */
    @Query("""
            select count(r) > 0 from AccessRevocation r
            where r.userId = :userId and r.expiresAt > :now
              and (r.sessionId = :sessionId or r.minVersion > :version)""")
    boolean isRevoked(UUID userId, UUID sessionId, int version, Instant now);

    @Modifying
    @Query("delete from AccessRevocation r where r.expiresAt < :before")
    int deleteExpiredBefore(Instant before);
}
