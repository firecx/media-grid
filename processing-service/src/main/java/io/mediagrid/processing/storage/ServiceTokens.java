package io.mediagrid.processing.storage;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import io.mediagrid.processing.config.ProcessingProperties;
import io.mediagrid.processing.job.JobFailure;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Токен службы (роль SERVICE) от службы авторизации: имя службы и её секрет. Токен хранится в памяти
 * и заменяется заранее, за минуту до истечения.
 */
@Component
public class ServiceTokens {

    static final Duration RENEW_BEFORE = Duration.ofMinutes(1);

    private final RestClient auth;
    private final String credentials;
    private String token;
    private Instant renewAt = Instant.MIN;

    public ServiceTokens(@LoadBalanced RestClient.Builder serviceRestClientBuilder, ProcessingProperties properties,
                         @Value("${spring.application.name}") String serviceName) {
        this.auth = serviceRestClientBuilder.clone()
                .baseUrl("http://auth-service")
                .requestFactory(ClientSetup.requestFactory(Duration.ofSeconds(2), Duration.ofSeconds(5)))
                .build();
        this.credentials = "Basic " + Base64.getEncoder().encodeToString(
                (serviceName + ":" + properties.clientSecret()).getBytes(StandardCharsets.UTF_8));
    }

    /** Значение заголовка Authorization. */
    public synchronized String bearer() {
        if (token == null || Instant.now().isAfter(renewAt)) {
            TokenResponse response = fetch();
            token = response.accessToken();
            renewAt = Instant.now().plusSeconds(response.expiresIn()).minus(RENEW_BEFORE);
        }
        return "Bearer " + token;
    }

    /** Служба отказала в доступе с этим токеном — в следующий раз получить новый. */
    public synchronized void invalidate() {
        token = null;
    }

    private TokenResponse fetch() {
        try {
            return auth.post().uri("/internal/auth/token")
                    .header(HttpHeaders.AUTHORIZATION, credentials)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().is2xxSuccessful()) {
                            return response.bodyTo(TokenResponse.class);
                        }
                        // Неверный секрет — ошибка настройки; задачи подождут, пока её исправят
                        throw new JobFailure.Transient(response.getStatusCode().value() == 401
                                ? "Служба авторизации не приняла секрет службы обработки (PROCESSING_CLIENT_SECRET)"
                                : "Служба авторизации ответила " + response.getStatusCode());
                    });
        } catch (RestClientException e) {
            throw new JobFailure.Transient("Служба авторизации недоступна: " + e.getMessage(), e);
        }
    }

    record TokenResponse(String accessToken, String tokenType, long expiresIn) {
    }
}
