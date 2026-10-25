package ru.hotelbooking.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import ru.hotelbooking.common.api.ApiError;

public final class SecurityErrors {
    private SecurityErrors() {}

    public static void write(
            ObjectMapper mapper,
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        mapper.writeValue(
                response.getOutputStream(),
                new ApiError(
                        Instant.now(),
                        status,
                        code,
                        message,
                        request.getRequestURI(),
                        MDC.get("requestId"),
                        Map.of()));
    }
}
