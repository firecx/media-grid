package io.mediagrid.gateway;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mediagrid.gateway.security.TokenRevocations;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static io.mediagrid.gateway.TestTokens.TRUSTED;
import static io.mediagrid.gateway.TestTokens.claims;
import static io.mediagrid.gateway.TestTokens.sign;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Отзыв токенов в шлюзе: список берётся у поддельной службы авторизации (токеном службы), отозванные
 * токены получают 401. Прочих служб нет — пропущенный запрос получает 503.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "mediagrid.gateway.revocations.client-secret=" + GatewayRevocationTest.SECRET,
        "mediagrid.gateway.revocations.poll-interval=100ms"})
@Import(TestTokens.Keys.class)
class GatewayRevocationTest {

    static final String SECRET = "test-gateway-secret-0123456789abcdef0";
    private static final String SERVICE_TOKEN = "service-token";

    private static final AtomicReference<String> list = new AtomicReference<>("[]");
    private static final AtomicInteger tokenRequests = new AtomicInteger();
    private static final AtomicInteger rejectedRequests = new AtomicInteger();
    private static final HttpServer auth = startAuthService();

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.auth-service[0].uri",
                () -> "http://localhost:" + auth.getAddress().getPort());
    }

    @AfterAll
    static void stopAuthService() {
        auth.stop(0);
    }

    @Value("${local.server.port}")
    int port;

    @Value("${mediagrid.security.issuer}")
    String issuer;

    @Autowired
    TokenRevocations revocations;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void revokedTokensAreRejectedUntilRevocationIsGone() throws InterruptedException {
        String user = UUID.randomUUID().toString();
        String loggedOut = UUID.randomUUID().toString();
        String otherSession = UUID.randomUUID().toString();
        String demoted = UUID.randomUUID().toString();
        String until = Instant.now().plusSeconds(900).toString();
        publish("""
                [{"userId": "%s", "sessionId": "%s", "minVersion": null, "expiresAt": "%s"},
                 {"userId": "%s", "sessionId": null, "minVersion": 2, "expiresAt": "%s"}]"""
                .formatted(user, loggedOut, until, demoted, until), 2);

        // Вход завершён — токены этого входа отклоняются, другого входа того же пользователя — нет
        expectRejected(token(user, loggedOut, 0));
        expectPassed(token(user, otherSession, 0));
        // Отозваны поколения меньше 2
        expectRejected(token(demoted, otherSession, 1));
        expectPassed(token(demoted, otherSession, 2));
        // Токен без утверждений sid и ver (выдан до 0.13.0) — поколение 0
        expectRejected(sign(TRUSTED, claims(issuer, "USER").subject(demoted)));

        publish("[]", 0);
        expectPassed(token(user, loggedOut, 0));

        // Токен службы получен один раз и используется повторно; без него список не отдаётся
        assertThat(tokenRequests.get()).isEqualTo(1);
        assertThat(rejectedRequests.get()).isZero();
    }

    /** Поддельная служба отдаёт новый список; ждём, пока шлюз его заберёт. */
    private void publish(String entries, int expectedSize) throws InterruptedException {
        list.set(entries);
        for (int i = 0; i < 100 && revocations.size() != expectedSize; i++) {
            Thread.sleep(50);
        }
        assertThat(revocations.size()).isEqualTo(expectedSize);
    }

    private void expectRejected(String token) {
        client.get().uri("/api/media/1").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
    }

    private void expectPassed(String token) {
        client.get().uri("/api/media/1").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .expectStatus().isEqualTo(503);
    }

    private String token(String userId, String sessionId, int version) {
        return sign(TRUSTED, claims(issuer, "USER").subject(userId).claim("sid", sessionId).claim("ver", version));
    }

    private static HttpServer startAuthService() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            String basic = "Basic " + Base64.getEncoder()
                    .encodeToString(("gateway:" + SECRET).getBytes(StandardCharsets.UTF_8));
            server.createContext("/internal/auth/token", exchange -> {
                tokenRequests.incrementAndGet();
                if ("POST".equals(exchange.getRequestMethod())
                        && basic.equals(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION))) {
                    respond(exchange, 200, """
                            {"accessToken": "%s", "tokenType": "Bearer", "expiresIn": 900}""".formatted(SERVICE_TOKEN));
                } else {
                    rejectedRequests.incrementAndGet();
                    respond(exchange, 401, "{}");
                }
            });
            server.createContext("/internal/auth/revocations", exchange -> {
                if (("Bearer " + SERVICE_TOKEN).equals(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION))) {
                    respond(exchange, 200, "{\"revocations\": " + list.get() + "}");
                } else {
                    rejectedRequests.incrementAndGet();
                    respond(exchange, 401, "{}");
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
