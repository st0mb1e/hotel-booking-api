package ru.hotelbooking.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.*;
import reactor.core.publisher.Mono;
import ru.hotelbooking.common.web.RequestIds;

@Component
public class RequestTraceFilter implements WebFilter, Ordered {
    private static final Logger log = LoggerFactory.getLogger(RequestTraceFilter.class);

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long started = System.nanoTime();
        String requestId =
                RequestIds.normalize(
                        exchange.getRequest().getHeaders().getFirst(RequestIds.HEADER));
        exchange.getResponse().getHeaders().set(RequestIds.HEADER, requestId);
        var traced =
                exchange.mutate()
                        .request(
                                exchange.getRequest()
                                        .mutate()
                                        .headers(
                                                headers ->
                                                        headers.set(RequestIds.HEADER, requestId))
                                        .build())
                        .build();
        exchange.getResponse()
                .beforeCommit(
                        () -> {
                            log.atInfo()
                                    .addKeyValue("event", "http_request")
                                    .addKeyValue("requestId", requestId)
                                    .addKeyValue("method", exchange.getRequest().getMethod())
                                    .addKeyValue("path", exchange.getRequest().getPath().value())
                                    .addKeyValue(
                                            "status",
                                            exchange.getResponse().getStatusCode() == null
                                                    ? 200
                                                    : exchange.getResponse()
                                                            .getStatusCode()
                                                            .value())
                                    .addKeyValue(
                                            "durationMs", (System.nanoTime() - started) / 1_000_000)
                                    .log("http_request");
                            return Mono.empty();
                        });
        return chain.filter(traced);
    }
}
