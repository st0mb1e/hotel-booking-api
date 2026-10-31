package ru.hotelbooking.booking.client;

import io.github.resilience4j.circuitbreaker.*;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.*;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class HotelCallPolicy {
    private static final Logger log = LoggerFactory.getLogger(HotelCallPolicy.class);
    private final Retry retry;
    private final CircuitBreaker operations;
    private final CircuitBreaker compensation;

    public HotelCallPolicy(HotelClientProperties properties) {
        retry = Retry.of("hotel", retryConfig(properties));
        var config = breakerConfig(properties);
        operations = CircuitBreaker.of("hotel-operations", config);
        compensation = CircuitBreaker.of("hotel-compensation", config);
        observe();
    }

    public <T> T executeOperation(Supplier<T> action) {
        return execute(action, operations);
    }

    public <T> T executeCompensation(Supplier<T> action) {
        return execute(action, compensation);
    }

    private <T> T execute(Supplier<T> action, CircuitBreaker breaker) {
        try {
            return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(breaker, action))
                    .get();
        } catch (CallNotPermittedException error) {
            throw new HotelUnavailableException("Hotel Service временно недоступен", error);
        }
    }

    private RetryConfig retryConfig(HotelClientProperties p) {
        return RetryConfig.custom()
                .maxAttempts(p.maxAttempts())
                .intervalFunction(
                        IntervalFunction.ofExponentialBackoff(
                                Math.max(1, p.retryDelay().toMillis()), 2.0))
                .retryOnException(error -> error instanceof HotelUnavailableException)
                .build();
    }

    private CircuitBreakerConfig breakerConfig(HotelClientProperties p) {
        return CircuitBreakerConfig.custom()
                .slidingWindowSize(Math.max(10, p.circuitBreakerMinimumCalls()))
                .minimumNumberOfCalls(p.circuitBreakerMinimumCalls())
                .failureRateThreshold(50)
                .waitDurationInOpenState(p.circuitBreakerOpenDuration())
                .recordException(error -> error instanceof HotelUnavailableException)
                .ignoreException(error -> error instanceof HotelRejectedException)
                .build();
    }

    private void observe() {
        for (var breaker : List.of(operations, compensation)) {
            breaker.getEventPublisher()
                    .onStateTransition(
                            event ->
                                    log.atWarn()
                                            .addKeyValue("event", "circuit_state")
                                            .addKeyValue("circuit", event.getCircuitBreakerName())
                                            .addKeyValue("transition", event.getStateTransition())
                                            .log("circuit_state"));
        }
        retry.getEventPublisher()
                .onRetry(
                        event ->
                                log.atWarn()
                                        .addKeyValue("event", "hotel_retry")
                                        .addKeyValue("attempt", event.getNumberOfRetryAttempts())
                                        .addKeyValue(
                                                "cause",
                                                event.getLastThrowable().getClass().getSimpleName())
                                        .log("hotel_retry"));
    }
}
