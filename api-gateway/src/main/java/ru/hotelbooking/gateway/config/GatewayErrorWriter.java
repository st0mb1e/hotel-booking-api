package ru.hotelbooking.gateway.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.hotelbooking.common.api.ApiError;
import ru.hotelbooking.common.web.RequestIds;

@Component
public class GatewayErrorWriter {
    private final ObjectMapper mapper;

    public GatewayErrorWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Mono<Void> write(
            ServerWebExchange exchange, HttpStatusCode status, String code, String message) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (status.value() == 401) response.getHeaders().set("WWW-Authenticate", "Bearer");
        String requestId = response.getHeaders().getFirst(RequestIds.HEADER);
        var error =
                new ApiError(
                        Instant.now(),
                        status.value(),
                        code,
                        message,
                        exchange.getRequest().getPath().value(),
                        requestId,
                        Map.of());
        try {
            byte[] body = mapper.writeValueAsBytes(error);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
        } catch (JsonProcessingException failure) {
            return Mono.error(failure);
        }
    }
}
