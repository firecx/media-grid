package io.mediagrid.auth.token;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.mediagrid.auth.config.AuthProperties;
import io.mediagrid.auth.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Отзыв токенов доступа. Токен проверяется по подписи, поэтому отзыв — это список, который шлюз забирает
 * у службы (GET /internal/auth/revocations) и сверяет с каждым запросом. Запись хранится, пока отозванный
 * токен мог бы ещё действовать: срок токена доступа плюс допуск на расхождение часов.
 */
@Service
public class AccessRevocationService {

    /** Проверка срока токена допускает расхождение часов в 60 секунд (JwtTimestampValidator). */
    static final Duration CLOCK_SKEW = Duration.ofMinutes(1);

    private static final Logger log = LoggerFactory.getLogger(AccessRevocationService.class);

    private final AccessRevocationRepository repository;
    private final Duration keepFor;

    public AccessRevocationService(AccessRevocationRepository repository, AuthProperties properties) {
        this.repository = repository;
        this.keepFor = properties.accessTokenTtl().plus(CLOCK_SKEW);
    }

    /** Отзыв токенов одного входа: выход, повторное предъявление заменённого обновляемого токена. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeSession(UUID userId, UUID sessionId) {
        repository.save(AccessRevocation.session(userId, sessionId, Instant.now().plus(keepFor)));
    }

    /**
     * Отзыв всех выданных пользователю токенов: следующее поколение токенов, прежние не принимаются.
     * Пользователь должен быть загружен в текущей транзакции — поколение сохраняется вместе с ним.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revokeAll(User user) {
        int version = user.nextTokenVersion();
        repository.save(AccessRevocation.olderThan(user.getId(), version, Instant.now().plus(keepFor)));
    }

    @Transactional(readOnly = true)
    public List<AccessRevocation> active() {
        return repository.findByExpiresAtAfterOrderByCreatedAt(Instant.now());
    }

    @Transactional(readOnly = true)
    public boolean isRevoked(UUID userId, UUID sessionId, int version) {
        return repository.isRevoked(userId, sessionId, version, Instant.now());
    }

    /** Раз в час удаляет записи, которые больше не нужны: отозванные ими токены уже истекли. */
    @Scheduled(cron = "0 15 * * * *")
    @Transactional
    public void deleteExpired() {
        int deleted = repository.deleteExpiredBefore(Instant.now());
        if (deleted > 0) {
            log.debug("Удалено устаревших записей об отзыве токенов: {}", deleted);
        }
    }
}
