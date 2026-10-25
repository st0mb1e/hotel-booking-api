package ru.hotelbooking.booking.client;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("hotel")
public record HotelClientProperties(
        @DefaultValue("") String baseUrl,
        @NotNull @DefaultValue("1s") Duration connectTimeout,
        @NotNull @DefaultValue("2s") Duration requestTimeout,
        @Min(1) @DefaultValue("3") int maxAttempts,
        @NotNull @DefaultValue("100ms") Duration retryDelay,
        @Min(1) @DefaultValue("5") int circuitBreakerMinimumCalls,
        @NotNull @DefaultValue("10s") Duration circuitBreakerOpenDuration) {
    public HotelClientProperties {
        if (connectTimeout == null
                || !connectTimeout.isPositive()
                || requestTimeout == null
                || !requestTimeout.isPositive()
                || retryDelay == null
                || retryDelay.isNegative()
                || circuitBreakerOpenDuration == null
                || !circuitBreakerOpenDuration.isPositive()) {
            throw new IllegalArgumentException("Тайм-ауты должны быть положительными");
        }
    }
}
