package io.mediagrid.auth.config;

import java.util.UUID;

import io.mediagrid.auth.token.AccessRevocationService;
import io.mediagrid.auth.token.AccessTokenService;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Служба авторизации сверяет токен с отзывами прямо по своей базе — без задержки, с которой список
 * получает шлюз. Например, сменить пароль токеном завершённого входа нельзя сразу после выхода.
 */
class RevokedTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error REVOKED = new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "Токен отозван", null);

    private final AccessRevocationService revocations;

    RevokedTokenValidator(AccessRevocationService revocations) {
        this.revocations = revocations;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        UUID userId = uuid(jwt.getSubject());
        if (userId == null) {
            // Токен службы: в нём имя службы, а не номер пользователя, и он не отзывается
            return OAuth2TokenValidatorResult.success();
        }
        Number version = jwt.getClaim(AccessTokenService.VERSION_CLAIM);
        boolean revoked = revocations.isRevoked(userId, uuid(jwt.getClaimAsString(AccessTokenService.SESSION_CLAIM)),
                version == null ? 0 : version.intValue());
        return revoked ? OAuth2TokenValidatorResult.failure(REVOKED) : OAuth2TokenValidatorResult.success();
    }

    private static UUID uuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
