package io.mediagrid.gateway.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Отклоняет отозванные токены: вход завершён (утверждение sid в списке) или токен прежнего поколения
 * (утверждение ver меньше отозванного). Отклонённый запрос получает 401 — клиент обновляет токен,
 * а если вход завершён, обновить его нельзя и нужно войти заново.
 */
class RevokedTokenValidator implements OAuth2TokenValidator<Jwt> {

    /** Утверждения токена службы авторизации (AccessTokenService). */
    static final String SESSION_CLAIM = "sid";
    static final String VERSION_CLAIM = "ver";

    private static final OAuth2Error REVOKED = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "Токен отозван", null);

    private final TokenRevocations revocations;

    RevokedTokenValidator(TokenRevocations revocations) {
        this.revocations = revocations;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Object version = jwt.getClaim(VERSION_CLAIM);
        boolean revoked = revocations.isRevoked(jwt.getSubject(), jwt.getClaimAsString(SESSION_CLAIM),
                version instanceof Number number ? number.intValue() : 0);
        return revoked ? OAuth2TokenValidatorResult.failure(REVOKED) : OAuth2TokenValidatorResult.success();
    }
}
