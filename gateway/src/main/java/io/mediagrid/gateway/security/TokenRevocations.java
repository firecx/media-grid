package io.mediagrid.gateway.security;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Отозванные токены доступа, которые ещё не истекли: копия списка службы авторизации в памяти шлюза
 * (обновляет RevocationSync). Проверка каждого запроса — два поиска в таблицах, без обращений по сети.
 */
@Component
public class TokenRevocations {

    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of());

    /** Заменяет список целиком: служба авторизации каждый раз отдаёт все действующие отзывы. */
    public void replace(List<Entry> entries) {
        Map<String, Instant> sessions = new HashMap<>();
        Map<String, Cutoff> users = new HashMap<>();
        for (Entry entry : entries) {
            if (entry.sessionId() != null) {
                sessions.merge(entry.sessionId(), entry.expiresAt(), (a, b) -> a.isAfter(b) ? a : b);
            } else if (entry.minVersion() != null) {
                // Отзыв более нового поколения перекрывает прежние и хранится не меньше их
                users.merge(entry.userId(), new Cutoff(entry.minVersion(), entry.expiresAt()), Cutoff::max);
            }
        }
        snapshot = new Snapshot(Map.copyOf(sessions), Map.copyOf(users));
    }

    /**
     * Отозван ли токен пользователя userId входа sessionId поколения version. Записи с истёкшим сроком
     * не учитываются: если список давно не обновлялся, устаревшие отзывы не мешают.
     */
    public boolean isRevoked(String userId, String sessionId, int version) {
        Snapshot current = snapshot;
        Instant now = Instant.now();
        if (sessionId != null) {
            Instant until = current.sessions().get(sessionId);
            if (until != null && until.isAfter(now)) {
                return true;
            }
        }
        Cutoff cutoff = userId == null ? null : current.users().get(userId);
        return cutoff != null && cutoff.expiresAt().isAfter(now) && version < cutoff.minVersion();
    }

    public int size() {
        Snapshot current = snapshot;
        return current.sessions().size() + current.users().size();
    }

    /** Запись списка службы авторизации (GET /internal/auth/revocations): задано sessionId или minVersion. */
    public record Entry(String userId, String sessionId, Integer minVersion, Instant expiresAt) {
    }

    private record Cutoff(int minVersion, Instant expiresAt) {

        static Cutoff max(Cutoff a, Cutoff b) {
            return new Cutoff(Math.max(a.minVersion, b.minVersion),
                    a.expiresAt.isAfter(b.expiresAt) ? a.expiresAt : b.expiresAt);
        }
    }

    private record Snapshot(Map<String, Instant> sessions, Map<String, Cutoff> users) {
    }
}
