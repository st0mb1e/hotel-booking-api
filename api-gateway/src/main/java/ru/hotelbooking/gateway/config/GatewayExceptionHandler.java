package ru.hotelbooking.gateway.config;

import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.hotelbooking.common.web.RequestIds;

@Component
@Order(-2)
public class GatewayExceptionHandler implements ErrorWebExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);
    private final GatewayErrorWriter writer;

    public GatewayExceptionHandler(GatewayErrorWriter writer) {
        this.writer = writer;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        if (exchange.getResponse().isCommitted()) return Mono.error(error);
        var failure = classify(error);
        log.atWarn()
                .addKeyValue("event", "gateway_request_failed")
                .addKeyValue(
                        "requestId",
                        exchange.getResponse().getHeaders().getFirst(RequestIds.HEADER))
                .addKeyValue("path", exchange.getRequest().getPath().value())
                .addKeyValue("status", failure.status().value())
                .addKeyValue("code", failure.code())
                .addKeyValue("cause", error.getClass().getSimpleName())
                .log("gateway_request_failed");
        return writer.write(exchange, failure.status(), failure.code(), failure.message());
    }

    private Failure classify(Throwable error) {
        if (error instanceof ResponseStatusException status) {
            return switch (status.getStatusCode().value()) {
                case 400 ->
                        new Failure(
                                HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Некорректный запрос");
                case 404 -> new Failure(HttpStatus.NOT_FOUND, "NOT_FOUND", "Ресурс не найден");
                case 405 ->
                        new Failure(
                                HttpStatus.METHOD_NOT_ALLOWED,
                                "METHOD_NOT_ALLOWED",
                                "Метод не поддерживается");
                case 503 -> unavailable();
                case 504 -> timeout();
                default ->
                        new Failure(
                                HttpStatus.valueOf(status.getStatusCode().value()),
                                "GATEWAY_ERROR",
                                "Ошибка обработки запроса");
            };
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException
                    || cause instanceof io.netty.handler.timeout.TimeoutException) {
                return timeout();
            }
            if (cause instanceof IOException
                    || cause instanceof reactor.netty.http.client.PrematureCloseException) {
                return unavailable();
            }
        }
        return new Failure(
                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Внутренняя ошибка шлюза");
    }

    private Failure unavailable() {
        return new Failure(
                HttpStatus.SERVICE_UNAVAILABLE,
                "UPSTREAM_UNAVAILABLE",
                "Сервис временно недоступен");
    }

    private Failure timeout() {
        return new Failure(
                HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT", "Истекло время ответа сервиса");
    }

    private record Failure(HttpStatus status, String code, String message) {}
}
