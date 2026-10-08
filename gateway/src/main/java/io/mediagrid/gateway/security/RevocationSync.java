package io.mediagrid.gateway.security;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.loadbalancer.reactive.ReactorLoadBalancerExchangeFilterFunction;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Раз в несколько секунд забирает у службы авторизации список отозванных токенов (TokenRevocations).
 * Если служба недоступна, остаётся прежний список; отзывы в нём истекают сами. Пока список не получен
 * ни разу (шлюз запущен, а служба авторизации — ещё нет), отозванные до запуска шлюза токены проходят:
 * они живут не дольше 15 минут, а остановить из-за этого весь вход было бы хуже.
 */
@Component
class RevocationSync {

    /** Служба авторизации запускается позже шлюза: о недоступности сообщать, если она затянулась. */
    private static final Duration WARN_AFTER = Duration.ofSeconds(60);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    /** Токен службы обновляется заранее, чтобы не истёк по дороге. */
    private static final Duration TOKEN_MARGIN = Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(RevocationSync.class);

    private final TokenRevocations revocations;
    private final RevocationProperties properties;
    // Отдельный клиент без наблюдения: опрос каждые несколько секунд не создаёт трасс
    private final WebClient client;

    private String token;
    private Instant tokenRenewAt = Instant.MIN;
    private Instant failingSince;
    private boolean warned;
    private boolean loaded;

    RevocationSync(TokenRevocations revocations, RevocationProperties properties,
                   ReactorLoadBalancerExchangeFilterFunction loadBalancer) {
        this.revocations = revocations;
        this.properties = properties;
        this.client = WebClient.builder().filter(loadBalancer).build();
        if (!enabled()) {
            log.warn("Не задан GATEWAY_CLIENT_SECRET: шлюз не получает список отозванных токенов и пропускает их "
                    + "до истечения срока");
        }
    }

    @Scheduled(fixedDelayString = "${mediagrid.gateway.revocations.poll-interval:5s}")
    void poll() {
        if (!enabled()) {
            return;
        }
        try {
            RevocationList list = client.get().uri(properties.listUri())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken())
                    .retrieve()
                    .bodyToMono(RevocationList.class)
                    .block(TIMEOUT);
            List<TokenRevocations.Entry> entries = list == null || list.revocations() == null
                    ? List.of() : list.revocations();
            revocations.replace(entries);
            if (!loaded || warned) {
                log.info("Список отозванных токенов получен: {} записей", revocations.size());
            }
            loaded = true;
            failingSince = null;
            warned = false;
        } catch (RuntimeException e) {
            // Токен мог быть отклонён (например, сменились ключи) — в следующий раз получить новый
            token = null;
            Instant now = Instant.now();
            if (failingSince == null) {
                failingSince = now;
            }
            if (!warned && Duration.between(failingSince, now).compareTo(WARN_AFTER) >= 0) {
                warned = true;
                log.warn("Список отозванных токенов не обновляется больше {} с: {}. Действует прежний список "
                        + "({} записей)", WARN_AFTER.toSeconds(), e.getMessage(), revocations.size());
            }
        }
    }

    private boolean enabled() {
        return properties.clientSecret() != null && !properties.clientSecret().isBlank();
    }

    private String serviceToken() {
        if (token == null || Instant.now().isAfter(tokenRenewAt)) {
            ServiceToken issued = client.post().uri(properties.tokenUri())
                    .headers(headers -> headers.setBasicAuth(properties.clientId(), properties.clientSecret()))
                    .retrieve()
                    .bodyToMono(ServiceToken.class)
                    .block(TIMEOUT);
            if (issued == null || issued.accessToken() == null) {
                throw new IllegalStateException("служба авторизации не выдала токен");
            }
            token = issued.accessToken();
            tokenRenewAt = Instant.now().plusSeconds(issued.expiresIn()).minus(TOKEN_MARGIN);
        }
        return token;
    }

    record ServiceToken(String accessToken, long expiresIn) {
    }

    record RevocationList(List<TokenRevocations.Entry> revocations) {
    }
}
