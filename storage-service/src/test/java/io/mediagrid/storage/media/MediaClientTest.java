package io.mediagrid.storage.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.UUID;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.mediagrid.support.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Повторы и размыкание цепи при обращении к службе медиаданных — на заглушке вместо сети. */
class MediaClientTest {

    private static final String TOKEN = "Bearer test";

    private MockRestServiceServer server;
    private MediaClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        Resilience4JCircuitBreakerFactory breakers = new Resilience4JCircuitBreakerFactory(
                CircuitBreakerRegistry.ofDefaults(), TimeLimiterRegistry.ofDefaults(), null);
        new MediaClient.Setup().mediaServiceBreaker().customize(breakers);
        client = new MediaClient(builder, breakers);
    }

    @Test
    void returnsRecordAndPassesUserToken() {
        UUID id = UUID.randomUUID();
        server.expect(once(), requestTo(url(id)))
                .andExpect(header(HttpHeaders.AUTHORIZATION, TOKEN))
                .andRespond(withSuccess(json(id), MediaType.APPLICATION_JSON));

        MediaInfo info = client.get(id, TOKEN);

        assertThat(info.id()).isEqualTo(id);
        assertThat(info.awaitingUpload()).isTrue();
        server.verify();
    }

    @Test
    void missingRecordIsNotRetried() {
        UUID id = UUID.randomUUID();
        server.expect(once(), requestTo(url(id))).andRespond(withResourceNotFound());

        assertThatThrownBy(() -> client.get(id, TOKEN))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        server.verify();
    }

    @Test
    void temporaryFailureIsRetried() {
        UUID id = UUID.randomUUID();
        server.expect(times(2), requestTo(url(id))).andRespond(withServerError());
        server.expect(once(), requestTo(url(id))).andRespond(withSuccess(json(id), MediaType.APPLICATION_JSON));

        assertThat(client.get(id, TOKEN).id()).isEqualTo(id);
        server.verify();
    }

    @Test
    void persistentFailureOpensCircuitAndLaterCallsFailFast() {
        UUID id = UUID.randomUUID();
        // 5 обращений по 3 попытки каждое — после них цепь размыкается
        server.expect(times(15), requestTo(url(id))).andRespond(withServiceUnavailable());
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> client.get(id, TOKEN))
                    .isInstanceOfSatisfying(ApiException.class,
                            e -> assertThat(e.getCode()).isEqualTo("SERVICE_UNAVAILABLE"));
        }

        // Шестое обращение — сразу 503, без запроса к службе медиаданных
        assertThatThrownBy(() -> client.get(id, TOKEN))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        server.verify();
    }

    private static String url(UUID id) {
        return "http://media-service/api/media/" + id;
    }

    private static String json(UUID id) {
        return "{\"id\": \"" + id + "\", \"ownerId\": \"" + UUID.randomUUID() + "\", \"title\": \"Клип\", "
                + "\"originalFilename\": \"clip.mp4\", \"contentType\": \"video/mp4\", \"sizeBytes\": 10, "
                + "\"status\": \"PENDING_UPLOAD\", \"tags\": []}";
    }
}
