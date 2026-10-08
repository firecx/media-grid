package io.mediagrid.gateway.error;

import java.net.ConnectException;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.support.ServiceUnavailableException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Ошибки самого шлюза (нет маршрута, служба недоступна, истекло время ожидания) — в формате ApiError.
 * Ответы служб, в том числе с ошибками, шлюз передаёт как есть.
 */
@Component
@Order(-2) // раньше стандартного обработчика Spring Boot
public class ApiErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorWebExceptionHandler.class);

    private final ApiErrorWriter writer;
    private final String retryAfterSeconds;

    /**
     * @param breakerOpenTime сколько цепь остаётся разомкнутой (config/gateway.yml) — через столько клиенту
     *                        имеет смысл повторить запрос
     */
    public ApiErrorWebExceptionHandler(ApiErrorWriter writer,
            @Value("${resilience4j.circuitbreaker.configs.default.wait-duration-in-open-state:10s}")
            Duration breakerOpenTime) {
        this.writer = writer;
        this.retryAfterSeconds = Long.toString(breakerOpenTime.toSeconds());
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (error instanceof AuthenticationServiceException) {
            // Токен нельзя проверить: служба авторизации недоступна, а ключей ещё нет
            log.warn("Не удалось проверить токен: {}", error.getMessage());
            return writer.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "Проверка входа временно недоступна, повторите запрос позже");
        }
        if (error instanceof ServiceUnavailableException) {
            // Цепь к службе разомкнута: запрос не отправлялся, служба недавно была недоступна
            log.debug("{} {} -> 503: цепь разомкнута", exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath());
            exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, retryAfterSeconds);
            return writer.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "Служба временно недоступна, повторите запрос позже");
        }
        HttpStatusCode status = error instanceof ResponseStatusException rse
                ? rse.getStatusCode()
                : isConnectFailure(error) ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR;
        if (status.is5xxServerError()) {
            log.warn("{} {} -> {}: {}", exchange.getRequest().getMethod(), exchange.getRequest().getPath(),
                    status.value(), error.getMessage());
        }
        return switch (status.value()) {
            case 404 -> writer.write(exchange, status, "NOT_FOUND", "Не найдено");
            case 405 -> writer.write(exchange, status, "METHOD_NOT_ALLOWED", "Метод не поддерживается");
            case 502 -> writer.write(exchange, status, "BAD_GATEWAY", "Служба вернула некорректный ответ");
            case 503 -> writer.write(exchange, status, "SERVICE_UNAVAILABLE", "Служба временно недоступна");
            case 504 -> writer.write(exchange, status, "GATEWAY_TIMEOUT", "Служба не ответила вовремя");
            default -> status.is4xxClientError()
                    ? writer.write(exchange, status, "BAD_REQUEST", "Некорректный запрос")
                    : writer.write(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                            "Внутренняя ошибка шлюза");
        };
    }

    /**
     * Служба не принимает соединения: остановлена, а регистр ещё не убрал её (запись живёт до 90 секунд).
     * Отказ и тайм-аут соединения у Netty — наследники ConnectException.
     */
    private static boolean isConnectFailure(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause()) {
            if (e instanceof ConnectException) {
                return true;
            }
        }
        return false;
    }
}
