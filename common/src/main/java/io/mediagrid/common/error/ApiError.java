package io.mediagrid.common.error;

import java.time.Instant;

/** Единый формат ошибки для всех служб. */
public record ApiError(String code, String message, String traceId, Instant timestamp) {

    public static ApiError of(String code, String message, String traceId) {
        return new ApiError(code, message, traceId, Instant.now());
    }
}