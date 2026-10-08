package io.mediagrid.gateway;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Повторы и размыкание цепи в шлюзе (настройки — config/gateway.yml). Вместо media-service — поддельная
 * служба в тесте, processing-service «остановлена»: её адрес никто не слушает.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestTokens.Keys.class)
class GatewayResilienceTest {

    private static final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private static final HttpServer media = startMediaService();

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.media-service[0].uri",
                () -> "http://localhost:" + media.getAddress().getPort());
        registry.add("spring.cloud.discovery.client.simple.instances.processing-service[0].uri",
                () -> "http://localhost:" + unusedPort());
    }

    @AfterAll
    static void stopMediaService() {
        media.stop(0);
    }

    @Value("${local.server.port}")
    int port;

    @Value("${mediagrid.security.issuer}")
    String issuer;

    @Autowired
    CircuitBreakerRegistry breakers;

    WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port)
                .defaultHeader(HttpHeaders.AUTHORIZATION, TestTokens.bearer(issuer, "USER"))
                .build();
    }

    @Test
    void readIsRetriedUntilServiceAnswers() {
        // Поддельная служба отвечает 503 на первые два обращения, на третье — 200
        client.get().uri("/api/media/flaky").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("ok");
        assertThat(calls("GET /api/media/flaky")).isEqualTo(3);
    }

    @Test
    void writeIsNotRetried() {
        client.post().uri("/api/media/flaky-write").exchange()
                .expectStatus().isEqualTo(503);
        assertThat(calls("POST /api/media/flaky-write")).isEqualTo(1);
    }

    @Test
    void internalErrorIsNotRetried() {
        client.get().uri("/api/media/boom").exchange()
                .expectStatus().isEqualTo(500);
        assertThat(calls("GET /api/media/boom")).isEqualTo(1);
    }

    @Test
    void serviceAnswering503DoesNotOpenCircuitAndItsAnswerPassesAsIs() {
        for (int i = 0; i < 12; i++) {
            client.get().uri("/api/media/down").exchange()
                    .expectStatus().isEqualTo(503)
                    .expectHeader().doesNotExist(HttpHeaders.RETRY_AFTER)
                    .expectBody().jsonPath("$.message").isEqualTo("media down");
        }
        // Каждое чтение — исходный запрос и 2 повтора
        assertThat(calls("GET /api/media/down")).isEqualTo(36);
        assertThat(breakers.circuitBreaker("media").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void circuitOpensWhenServiceIsUnreachable() {
        CircuitBreaker breaker = breakers.circuitBreaker("processing");
        // Настройки config/gateway.yml применились к размыкателю маршрута
        assertThat(breaker.getCircuitBreakerConfig().getMinimumNumberOfCalls()).isEqualTo(10);
        for (int i = 0; i < 10; i++) {
            client.get().uri("/api/processing/x").exchange()
                    .expectStatus().isEqualTo(503)
                    .expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        client.get().uri("/api/processing/x").exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "10")
                .expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");
    }

    private static int calls(String key) {
        return calls.getOrDefault(key, new AtomicInteger()).get();
    }

    private static HttpServer startMediaService() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
                int n = calls.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
                switch (exchange.getRequestURI().getPath()) {
                    case "/api/media/flaky" -> respond(exchange, n <= 2 ? 503 : 200, n <= 2 ? "busy" : "ok");
                    case "/api/media/boom" -> respond(exchange, 500, "boom");
                    default -> respond(exchange, 503,
                            "{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"media down\"}");
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
        exchange.getResponseHeaders().set("Content-Type", body.startsWith("{") ? "application/json" : "text/plain");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
