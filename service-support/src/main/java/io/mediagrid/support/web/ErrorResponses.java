package io.mediagrid.support.web;

import java.io.IOException;

import io.mediagrid.common.error.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/** Запись ответа об ошибке в формате ApiError там, где обработчик ошибок Spring MVC не действует (фильтры). */
public final class ErrorResponses {

    private ErrorResponses() {
    }

    public static void write(HttpServletResponse response, JsonMapper json, HttpStatus status, String code,
                             String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), ApiError.of(code, message, TraceIds.current()));
    }
}
