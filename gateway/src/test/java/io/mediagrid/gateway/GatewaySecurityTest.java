package io.mediagrid.gateway;

import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import static io.mediagrid.gateway.TestTokens.TRUSTED;
import static io.mediagrid.gateway.TestTokens.claims;
import static io.mediagrid.gateway.TestTokens.sign;

/**
 * Проверка токенов в шлюзе. Служб за шлюзом нет: запрос, прошедший проверку,
 * получает 503 SERVICE_UNAVAILABLE, остановленный шлюзом — 401 или 403.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestTokens.Keys.class)
class GatewaySecurityTest {

    private static final KeyPair FOREIGN = TestTokens.generateKeyPair();

    @Value("${local.server.port}")
    int port;

    @Value("${mediagrid.security.issuer}")
    String issuer;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void requestWithoutTokenIsStoppedAtGateway() {
        client.get().uri("/api/media/1").exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED")
                .jsonPath("$.timestamp").isNotEmpty()
                // Номер трассы запроса — по нему ошибку находят в журналах
                .jsonPath("$.traceId").value(String.class,
                        traceId -> org.assertj.core.api.Assertions.assertThat(traceId).matches("[0-9a-f]{32}"));
    }

    @Test
    void requestWithValidTokenIsPassedToService() {
        client.get().uri("/api/media/1").header(HttpHeaders.AUTHORIZATION, bearer(token("USER")))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    void loginAndRefreshDoNotNeedToken() {
        client.post().uri("/api/auth/login").exchange().expectStatus().isEqualTo(503);
        client.post().uri("/api/auth/refresh").exchange().expectStatus().isEqualTo(503);
        client.get().uri("/api/auth/jwks").exchange().expectStatus().isEqualTo(503);
    }

    @Test
    void fileContentByLinkDoesNotNeedTokenButOtherFileOperationsDo() {
        client.get().uri("/api/files/" + UUID.randomUUID() + "/content?expires=1&signature=x")
                .exchange().expectStatus().isEqualTo(503);
        client.put().uri("/api/files/" + UUID.randomUUID()).exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/files/" + UUID.randomUUID() + "/links").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void serviceTokenDoesNotOpenUserPathsOrInternalPaths() {
        client.get().uri("/api/media/1").header(HttpHeaders.AUTHORIZATION, bearer(token("SERVICE")))
                .exchange()
                .expectStatus().isForbidden();
        // Внутренние пути служб наружу не выпускаются ни с каким токеном
        client.post().uri("/internal/auth/token").header(HttpHeaders.AUTHORIZATION, bearer(token("ADMIN")))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void otherAuthEndpointsNeedToken() {
        client.get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/auth/login").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void adminPathsNeedAdminRole() {
        client.get().uri("/api/auth/admin/users").header(HttpHeaders.AUTHORIZATION, bearer(token("USER")))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
        client.get().uri("/api/media/admin/anything").header(HttpHeaders.AUTHORIZATION, bearer(token("USER")))
                .exchange()
                .expectStatus().isForbidden();
        client.get().uri("/api/auth/admin/users").header(HttpHeaders.AUTHORIZATION, bearer(token("ADMIN")))
                .exchange()
                .expectStatus().isEqualTo(503);
    }

    @Test
    void expiredTokenIsRejected() {
        Instant past = Instant.now().minus(Duration.ofHours(1));
        String expired = sign(TRUSTED, claims(issuer, "USER").issuedAt(past).expiresAt(past.plusSeconds(60)));
        client.get().uri("/api/media/1").header(HttpHeaders.AUTHORIZATION, bearer(expired))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void tokenFromOtherIssuerIsRejected() {
        String other = sign(TRUSTED, claims("someone-else", "USER"));
        client.get().uri("/api/media/1").header(HttpHeaders.AUTHORIZATION, bearer(other))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void tokenSignedWithForeignKeyIsRejected() {
        String forged = sign(FOREIGN, claims(issuer, "ADMIN"));
        client.get().uri("/api/auth/admin/users").header(HttpHeaders.AUTHORIZATION, bearer(forged))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void pathsOutsideApiAreClosed() {
        client.get().uri("/anything").header(HttpHeaders.AUTHORIZATION, bearer(token("ADMIN")))
                .exchange()
                .expectStatus().isForbidden();
    }

    private String token(String role) {
        return sign(TRUSTED, claims(issuer, role));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
