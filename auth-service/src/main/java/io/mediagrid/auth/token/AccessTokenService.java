package io.mediagrid.auth.token;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.mediagrid.auth.config.AuthProperties;
import io.mediagrid.auth.key.SigningKeyService;
import io.mediagrid.auth.user.User;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Токены доступа: подписанные, короткоживущие, проверяются любой службой по открытому ключу
 * без обращения к службе авторизации. Поэтому отозвать выданный токен доступа нельзя —
 * он просто истекает; отзываются обновляемые токены.
 */
@Service
public class AccessTokenService {

    /** Утверждение со списком ролей; службы превращают его в права ROLE_*. */
    public static final String ROLES_CLAIM = "roles";

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

    public String issue(User user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(user.getId().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim("email", user.getEmail())
                .claim(ROLES_CLAIM, List.of(user.getRole().name()))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keys.activeKid()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public Duration ttl() {
        return ttl;
    }
}
