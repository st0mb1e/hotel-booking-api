package ru.hotelbooking.booking.client;

import com.fasterxml.jackson.databind.*;
import io.github.resilience4j.circuitbreaker.*;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;
import ru.hotelbooking.booking.service.TokenService;
import ru.hotelbooking.common.api.PageResponse;
import ru.hotelbooking.common.contract.*;
import ru.hotelbooking.common.web.RequestIds;

@Component
public class HttpHotelClient implements HotelClient {
    private static final Logger log = LoggerFactory.getLogger(HttpHotelClient.class);
    private final DiscoveryClient discovery;
    private final TokenService tokens;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String baseUrl;
    private final Duration timeout;
    private final Retry retry;
    private final CircuitBreaker operations;
    private final CircuitBreaker compensation;
    private final AtomicInteger nextInstance = new AtomicInteger();

    public HttpHotelClient(
            DiscoveryClient discovery,
            TokenService tokens,
            ObjectMapper mapper,
            @Value("${hotel.base-url:}") String baseUrl,
            @Value("${hotel.connect-timeout:1s}") Duration connectTimeout,
            @Value("${hotel.request-timeout:2s}") Duration timeout,
            @Value("${hotel.max-attempts:3}") int attempts,
            @Value("${hotel.retry-delay:100ms}") Duration delay,
            @Value("${hotel.circuit-breaker-minimum-calls:5}") int minimumCalls,
            @Value("${hotel.circuit-breaker-open-duration:10s}") Duration openDuration) {
        this.discovery = discovery;
        this.tokens = tokens;
        this.mapper = mapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        this.retry =
                Retry.of(
                        "hotel",
                        RetryConfig.custom()
                                .maxAttempts(attempts)
                                .intervalFunction(
                                        IntervalFunction.ofExponentialBackoff(
                                                delay.toMillis(), 2.0))
                                .retryOnException(
                                        error -> error instanceof HotelUnavailableException)
                                .build());
        var config =
                CircuitBreakerConfig.custom()
                        .slidingWindowSize(Math.max(10, minimumCalls))
                        .minimumNumberOfCalls(minimumCalls)
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(openDuration)
                        .recordException(error -> error instanceof HotelUnavailableException)
                        .ignoreException(error -> error instanceof HotelRejectedException)
                        .build();
        operations = CircuitBreaker.of("hotel-operations", config);
        compensation = CircuitBreaker.of("hotel-compensation", config);
        for (var breaker : List.of(operations, compensation)) {
            breaker.getEventPublisher()
                    .onStateTransition(
                            event -> {
                                log.atWarn()
                                        .addKeyValue("event", "circuit_state")
                                        .addKeyValue("circuit", event.getCircuitBreakerName())
                                        .addKeyValue("transition", event.getStateTransition())
                                        .log("circuit_state");
                            });
        }
        retry.getEventPublisher()
                .onRetry(
                        event -> {
                            log.atWarn()
                                    .addKeyValue("event", "hotel_retry")
                                    .addKeyValue("attempt", event.getNumberOfRetryAttempts())
                                    .addKeyValue(
                                            "cause",
                                            event.getLastThrowable().getClass().getSimpleName())
                                    .log("hotel_retry");
                        });
    }

    @Override
    public List<RoomSummary> recommend(LocalDate start, LocalDate end, Long hotelId) {
        var all = new LinkedHashMap<Long, RoomSummary>();
        JavaType type =
                mapper.getTypeFactory()
                        .constructParametricType(PageResponse.class, RoomSummary.class);
        for (int page = 0; ; page++) {
            String path =
                    "/api/rooms/recommend?startDate="
                            + start
                            + "&endDate="
                            + end
                            + "&page="
                            + page
                            + "&size=100"
                            + (hotelId == null ? "" : "&hotelId=" + hotelId);
            PageResponse<RoomSummary> result =
                    execute(() -> request("GET", path, null, type), operations);
            result.content().forEach(room -> all.put(room.id(), room));
            if (page + 1 >= result.totalPages()) break;
        }
        return all.values().stream()
                .sorted(
                        Comparator.comparingLong(RoomSummary::timesBooked)
                                .thenComparingLong(RoomSummary::id))
                .toList();
    }

    @Override
    public void confirm(long roomId, ReservationCommand command) {
        ReservationReceipt receipt =
                execute(
                        () ->
                                request(
                                        "POST",
                                        "/api/rooms/" + roomId + "/confirm-availability",
                                        command,
                                        mapper.constructType(ReservationReceipt.class)),
                        operations);
        if (!command.bookingId().equals(receipt.bookingId())
                || roomId != receipt.roomId()
                || !"RESERVED".equals(receipt.status())) {
            throw new HotelUnavailableException("Hotel Service вернул некорректное подтверждение");
        }
    }

    @Override
    public void release(long roomId, ReleaseCommand command) {
        ReservationReceipt receipt =
                execute(
                        () ->
                                request(
                                        "POST",
                                        "/api/rooms/" + roomId + "/release",
                                        command,
                                        mapper.constructType(ReservationReceipt.class)),
                        compensation);
        if (!command.bookingId().equals(receipt.bookingId())
                || roomId != receipt.roomId()
                || !"RELEASED".equals(receipt.status())) {
            throw new HotelUnavailableException(
                    "Hotel Service вернул некорректный результат отмены");
        }
    }

    private <T> T execute(Supplier<T> action, CircuitBreaker breaker) {
        try {
            return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(breaker, action))
                    .get();
        } catch (CallNotPermittedException e) {
            throw new HotelUnavailableException("Hotel Service временно недоступен", e);
        }
    }

    private <T> T request(String method, String path, Object body, JavaType type) {
        try {
            var builder =
                    HttpRequest.newBuilder(resolve().resolve(path))
                            .timeout(timeout)
                            .header("Authorization", "Bearer " + tokens.serviceToken())
                            .header("Accept", "application/json")
                            .header(RequestIds.HEADER, RequestIds.normalize(MDC.get("requestId")));
            if (body == null) builder.GET();
            else
                builder.header("Content-Type", "application/json")
                        .method(
                                method,
                                HttpRequest.BodyPublishers.ofString(
                                        mapper.writeValueAsString(body)));
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 500 || response.statusCode() == 429)
                throw new HotelUnavailableException(
                        "Hotel Service вернул " + response.statusCode());
            if (response.statusCode() >= 400) {
                JsonNode error = mapper.readTree(response.body());
                throw new HotelRejectedException(
                        response.statusCode(),
                        error.path("code").asText("HOTEL_REJECTED"),
                        error.path("message").asText("Hotel Service отклонил запрос"));
            }
            return mapper.readValue(response.body(), type);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HotelUnavailableException("Вызов Hotel Service прерван", e);
        } catch (IOException e) {
            throw new HotelUnavailableException("Ошибка связи с Hotel Service", e);
        }
    }

    private URI resolve() {
        if (!baseUrl.isBlank()) return URI.create(baseUrl);
        var instances = discovery.getInstances("hotel-service");
        if (instances.isEmpty())
            throw new HotelUnavailableException("Hotel Service не найден в Eureka");
        return instances
                .get(Math.floorMod(nextInstance.getAndIncrement(), instances.size()))
                .getUri();
    }
}
