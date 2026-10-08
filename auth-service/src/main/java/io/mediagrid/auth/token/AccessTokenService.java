package io.mediagrid.auth.token;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.mediagrid.auth.config.AuthProperties;
import io.mediagrid.auth.key.SigningKeyService;
import io.mediagrid.auth.user.User;
import io.mediagrid.support.security.ResourceServerSecurity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Токены доступа: подписанные, короткоживущие, проверяются любой службой по открытому ключу
 * без обращения к службе авторизации. Отозванные до срока токены (выход, смена пароля, отключение,
 * смена роли) перечисляет AccessRevocationService — по входу (sid) или по поколению (ver).
 */
@Service
public class AccessTokenService {

    /** Утверждение со списком ролей; службы превращают его в права ROLE_*. */
    public static final String ROLES_CLAIM = ResourceServerSecurity.ROLES_CLAIM;

    /** Номер входа — цепочки обновляемых токенов (family_id): по нему отзываются токены одного входа. */
    public static final String SESSION_CLAIM = "sid";

    /** Поколение токенов пользователя: по нему отзываются все токены пользователя сразу. */
    public static final String VERSION_CLAIM = "ver";

    private final JwtEncoder encoder;
    private final SigningKeyService keys;
    private final String issuer;
    private final Duration ttl;

    public AccessTokenService(JwtEncoder encoder, SigningKeyService keys, AuthProperties properties) {
        this.encoder = encoder;
        this.keys = keys;
        this.issuer = properties.issuer();
        this.ttl = properties.accessTokenTtl();
    }

    public String issue(User user, UUID sessionId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(user.getId().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim("email", user.getEmail())
                .claim(ROLES_CLAIM, List.of(user.getRole().name()))
                .claim(SESSION_CLAIM, sessionId.toString())
                .claim(VERSION_CLAIM, user.getTokenVersion())
                .build();
        return sign(claims);
    }

    /** Токен службы: вместо номера пользователя — имя службы, единственная роль SERVICE. */
    public String issueForService(String service) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(service)
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim(ROLES_CLAIM, List.of(ResourceServerSecurity.SERVICE_ROLE))
                .build();
        return sign(claims);
    }

    private String sign(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.activeKid()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public Duration ttl() {
        return ttl;
    }
}
