package io.mediagrid.auth.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.mediagrid.auth.token.AccessRevocation;
import io.mediagrid.auth.token.AccessRevocationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Список отозванных токенов доступа, которые ещё не истекли. Шлюз забирает его каждые несколько секунд
 * (токеном службы) и не пропускает такие токены к службам.
 */
@RestController
@RequestMapping("/internal/auth")
public class RevocationController {

    private final AccessRevocationService revocations;

    public RevocationController(AccessRevocationService revocations) {
        this.revocations = revocations;
    }

    @GetMapping("/revocations")
    public RevocationList revocations() {
        return new RevocationList(revocations.active().stream().map(Entry::of).toList());
    }

    public record RevocationList(List<Entry> revocations) {
    }

    /**
     * Отозваны токены пользователя userId: входа sessionId (утверждение sid) или все поколения меньше
     * minVersion (утверждение ver). Задано одно из двух. После expiresAt запись не нужна.
     */
    public record Entry(UUID userId, UUID sessionId, Integer minVersion, Instant expiresAt) {

        static Entry of(AccessRevocation revocation) {
            return new Entry(revocation.getUserId(), revocation.getSessionId(), revocation.getMinVersion(),
                    revocation.getExpiresAt());
        }
    }
}
