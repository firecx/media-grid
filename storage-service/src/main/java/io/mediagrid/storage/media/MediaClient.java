package io.mediagrid.storage.media;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;

import io.mediagrid.support.web.ApiException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.restclient.autoconfigure.RestClientBuilderConfigurer;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Обращение к службе медиаданных от имени пользователя: служба хранения передаёт его токен,
 * и права на запись проверяет сама служба медиаданных. Своих учётных данных у служб нет.
 * <p>
 * Устойчивость (ТЗ, п. 4.1.1): при временной недоступности — повторы с нарастающей паузой,
 * при устойчивом сбое — размыкание цепи: запросы сразу получают 503, не дожидаясь тайм-аутов.
 */
@Component
public class MediaClient {

    static final String BREAKER = "media-service";

    private static final Logger log = LoggerFactory.getLogger(MediaClient.class);

    private final RestClient rest;
    private final CircuitBreaker breaker;
    private final RetryTemplate retry;

    public MediaClient(@LoadBalanced RestClient.Builder mediaRestClientBuilder,
                       CircuitBreakerFactory<?, ?> breakers) {
        this.rest = mediaRestClientBuilder.baseUrl("http://media-service").build();
        this.breaker = breakers.create(BREAKER);
        this.retry = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(2)
                .delay(Duration.ofMillis(200))
                .multiplier(2)
                .predicate(MediaClient::isTransient)
                .build());
    }

    /**
     * Запись каталога глазами пользователя. 404 — записи нет или она пользователю не видна;
     * 401/403 передаются как есть; недоступность службы медиаданных — 503.
     */
    public MediaInfo get(UUID mediaId, String authorization) {
        return breaker.run(() -> {
            try {
                return retry.execute(() -> fetch(mediaId, authorization));
            } catch (RetryException e) {
                throw e.getCause() instanceof RuntimeException cause ? cause : new IllegalStateException(e);
            }
        }, MediaClient::fallback);
    }

    private MediaInfo fetch(UUID mediaId, String authorization) {
        return rest.get()
                .uri("/api/media/{id}", mediaId)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .exchange((request, response) -> {
                    HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
                    if (status == HttpStatus.OK) {
                        return response.bodyTo(MediaInfo.class);
                    }
                    if (status == HttpStatus.NOT_FOUND) {
                        throw ApiException.notFound("Файл не найден");
                    }
                    if (status == HttpStatus.UNAUTHORIZED) {
                        throw ApiException.unauthorized("UNAUTHORIZED", "Требуется вход в систему");
                    }
                    if (status == HttpStatus.FORBIDDEN) {
                        throw ApiException.forbidden("FORBIDDEN", "Недостаточно прав");
                    }
                    throw new MediaServiceUnavailable("служба медиаданных ответила " + response.getStatusCode());
                });
    }

    /** Повторять имеет смысл только сбои связи и временную недоступность, но не ответы вида «нет прав». */
    private static boolean isTransient(Throwable error) {
        return !(error instanceof ApiException);
    }

    private static MediaInfo fallback(Throwable error) {
        if (error instanceof ApiException apiError) {
            throw apiError;
        }
        log.warn("Служба медиаданных недоступна: {}", error.toString());
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "Служба медиаданных временно недоступна, повторите запрос позже");
    }

    /** Служба медиаданных ответила ошибкой со своей стороны (5xx) — это сбой, а не отказ в доступе. */
    static class MediaServiceUnavailable extends RuntimeException {
        MediaServiceUnavailable(String message) {
            super(message);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Setup {

        /**
         * Клиент с балансировщиком и короткими тайм-аутами: зависший ответ не держит запрос пользователя.
         * defaultCandidate = false: компонент получает только тот, кто явно просит @LoadBalanced. Иначе он
         * вытеснил бы стандартный RestClient.Builder, и клиент регистра (Eureka) стал бы искать сам регистр
         * через балансировщик — и не смог бы зарегистрироваться.
         */
        @Bean(defaultCandidate = false)
        @LoadBalanced
        RestClient.Builder mediaRestClientBuilder(RestClientBuilderConfigurer configurer) {
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
            factory.setReadTimeout(Duration.ofSeconds(5));
            // Настройки Spring Boot (в том числе трассировка: номер трассы уходит в заголовке traceparent),
            // затем свои тайм-ауты
            return configurer.configure(RestClient.builder()).requestFactory(factory);
        }

        /**
         * Цепь размыкается, если из последних 10 обращений (не меньше 5) половина закончилась сбоем,
         * и через 15 секунд пробует снова. Ответы «нет записи» и «нет прав» сбоем не считаются.
         * <p>
         * Размыкатель выполняет вызов в своём пуле потоков (так работает ограничение времени). Пул
         * переносит в поток контекст запроса, иначе обращение к службе медиаданных попало бы
         * в отдельную трассу.
         */
        @Bean
        Customizer<Resilience4JCircuitBreakerFactory> mediaServiceBreaker() {
            return factory -> {
                factory.configureExecutorService(ContextExecutorService.wrap(
                        Executors.newCachedThreadPool(), ContextSnapshotFactory.builder().build()));
                configureBreaker(factory);
            };
        }

        private static void configureBreaker(Resilience4JCircuitBreakerFactory factory) {
            factory.configure(builder -> builder
                    .circuitBreakerConfig(CircuitBreakerConfig.custom()
                            .slidingWindowSize(10)
                            .minimumNumberOfCalls(5)
                            .failureRateThreshold(50)
                            .waitDurationInOpenState(Duration.ofSeconds(15))
                            .ignoreExceptions(ApiException.class)
                            .build())
                    // Время ограничивают тайм-ауты клиента; это — верхняя граница с учётом повторов
                    .timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(20)).build()),
                    BREAKER);
        }
    }
}
