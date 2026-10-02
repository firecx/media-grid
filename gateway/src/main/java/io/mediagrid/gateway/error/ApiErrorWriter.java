package io.mediagrid.gateway.error;

import io.mediagrid.common.error.ApiError;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/** Пишет ответ об ошибке в едином формате ApiError. */
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
        // traceId появится вместе со сквозной трассировкой
        byte[] body = json.writeValueAsBytes(ApiError.of(code, message, null));
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
