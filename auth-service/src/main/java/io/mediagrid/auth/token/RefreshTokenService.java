package io.mediagrid.auth.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import io.mediagrid.auth.config.AuthProperties;
import io.mediagrid.auth.user.User;
import io.mediagrid.support.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Обновляемые токены: случайная строка у клиента, в базе только её отпечаток.
 * При каждом обновлении токен заменяется новым; повторное предъявление уже заменённого
 * токена означает, что его кто-то перехватил, и вся цепочка входа отзывается.
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository repository;
    private final AccessRevocationService accessRevocations;
    private final Duration ttl;

    public RefreshTokenService(RefreshTokenRepository repository, AccessRevocationService accessRevocations,
                               AuthProperties properties) {
        this.repository = repository;
        this.accessRevocations = accessRevocations;
        this.ttl = properties.refreshTokenTtl();
    }

    /** Новый токен для нового входа. */
    @Transactional
    public IssuedRefreshToken issue(User user) {
        return issue(user, UUID.randomUUID());
    }

    /** Обмен действующего токена на новый. Возвращает новый токен и его владельца. */
    @Transactional(noRollbackFor = ApiException.class)
    public Rotation rotate(String rawToken) {
        Instant now = Instant.now();
        RefreshToken current = repository.findByTokenHash(hash(rawToken)).orElseThrow(RefreshTokenService::invalid);
        if (current.isRevoked()) {
            if (current.getReplacedBy() != null) {
                int revoked = revokeSession(current, now);
                log.warn("Повторно предъявлен заменённый обновляемый токен пользователя {}: цепочка отозвана ({} шт.)",
                        current.getUser().getId(), revoked);
            }
            throw invalid();
        }
        if (current.isExpired(now) || !current.getUser().isEnabled()) {
            throw invalid();
        }
        IssuedRefreshToken next = issue(current.getUser(), current.getFamilyId());
        current.revoke(now, next.id());
        return new Rotation(current.getUser(), next);
    }

    /** Выход: отзывается вся цепочка текущего входа. Неизвестный токен молча игнорируется. */
    @Transactional
    public void revoke(String rawToken) {
        repository.findByTokenHash(hash(rawToken)).ifPresent(token -> revokeSession(token, Instant.now()));
    }

    /** Завершает все входы пользователя: обновляемые токены и уже выданные токены доступа. */
    @Transactional
    public void revokeAllForUser(User user) {
        repository.revokeAllForUser(user.getId(), Instant.now());
        accessRevocations.revokeAll(user);
    }

    /**
     * Отзывает цепочку входа и выданные по ней токены доступа. Если в цепочке не осталось действующих
     * токенов, вход завершён раньше — и токены доступа отозваны тогда же.
     */
    private int revokeSession(RefreshToken token, Instant now) {
        int revoked = repository.revokeFamily(token.getFamilyId(), now);
        if (revoked > 0) {
            accessRevocations.revokeSession(token.getUser().getId(), token.getFamilyId());
        }
        return revoked;
    }

    /** Раз в сутки удаляет токены, истёкшие больше суток назад. */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void deleteExpired() {
        int deleted = repository.deleteExpiredBefore(Instant.now().minus(Duration.ofDays(1)));
        if (deleted > 0) {
            log.info("Удалено истёкших обновляемых токенов: {}", deleted);
        }
    }

    public Duration ttl() {
        return ttl;
    }

    private IssuedRefreshToken issue(User user, UUID familyId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        RefreshToken token = repository.save(new RefreshToken(user, hash(raw), familyId, Instant.now().plus(ttl)));
        return new IssuedRefreshToken(token.getId(), familyId, raw);
    }

    private static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException invalid() {
        return ApiException.unauthorized("INVALID_REFRESH_TOKEN", "Сеанс истёк, войдите заново");
    }

    /** familyId — номер входа, попадает в токен доступа (sid). */
    public record IssuedRefreshToken(UUID id, UUID familyId, String value) {
    }

    public record Rotation(User user, IssuedRefreshToken token) {
    }
}
