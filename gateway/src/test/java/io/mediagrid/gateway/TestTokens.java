package io.mediagrid.gateway;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import io.mediagrid.gateway.security.SecurityConfig;
import io.mediagrid.gateway.security.TokenRevocations;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/** Токены для тестов шлюза: подписываются ключом теста, шлюз проверяет их этим же ключом. */
final class TestTokens {

    static final KeyPair TRUSTED = generateKeyPair();

    private TestTokens() {
    }

    /** Вместо ключей службы авторизации — ключ теста. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Keys {

        @Bean
        @Primary
        ReactiveJwtDecoder testJwtDecoder(@Value("${mediagrid.security.issuer}") String issuer,
                                          TokenRevocations revocations) {
            NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                    .withPublicKey((RSAPublicKey) TRUSTED.getPublic()).build();
            decoder.setJwtValidator(SecurityConfig.jwtValidator(issuer, revocations));
            return decoder;
        }
    }

    static String bearer(String issuer, String role) {
        return "Bearer " + sign(TRUSTED, claims(issuer, role));
    }

    static JwtClaimsSet.Builder claims(String issuer, String role) {
        Instant now = Instant.now();
        return JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(15)))
                .claim("roles", List.of(role));
    }

    static String sign(KeyPair keyPair, JwtClaimsSet.Builder claims) {
        RSAKey key = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID("test")
                .build();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("test").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
