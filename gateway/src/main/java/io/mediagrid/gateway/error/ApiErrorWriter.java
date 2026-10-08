package io.mediagrid.gateway.error;

import io.mediagrid.common.error.ApiError;
import io.micrometer.tracing.handler.TracingObservationHandler.TracingContext;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** Пишет ответ об ошибке в едином формате ApiError — с номером трассы запроса. */
@Component
public class ApiErrorWriter {

    private final JsonMapper json;

    public ApiErrorWriter(JsonMapper json) {
        this.json = json;
    }

    public Mono<Void> write(ServerWebExchange exchange, HttpStatusCode status, String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = json.writeValueAsBytes(ApiError.of(code, message, traceId(exchange)));
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * Номер трассы запроса. В реактивном шлюзе контекст журнала (MDC) не привязан к потоку, поэтому
     * номер берётся из наблюдения за запросом, которое Spring хранит в атрибутах обмена.
     */
    static String traceId(ServerWebExchange exchange) {
        return ServerRequestObservationContext.findCurrent(exchange.getAttributes())
                .map(context -> context.<TracingContext>get(TracingContext.class))
                .map(TracingContext::getSpan)
                .map(span -> span.context().traceId())
                .orElse(null);
    }
}
