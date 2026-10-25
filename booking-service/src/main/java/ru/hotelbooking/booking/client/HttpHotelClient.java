package ru.hotelbooking.booking.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import ru.hotelbooking.booking.service.TokenService;
import ru.hotelbooking.common.contract.*;
import ru.hotelbooking.common.web.RequestIds;

@Component
public class HttpHotelClient implements HotelClient {
    private final HttpClient http;
    private final HotelEndpointResolver endpoints;
    private final HotelCallPolicy calls;
    private final HotelClientProperties properties;
    private final ObjectMapper mapper;
    private final TokenService tokens;

    public HttpHotelClient(
            HttpClient http,
            HotelEndpointResolver endpoints,
            HotelCallPolicy calls,
            HotelClientProperties properties,
            ObjectMapper mapper,
            TokenService tokens) {
        this.http = http;
        this.endpoints = endpoints;
        this.calls = calls;
        this.properties = properties;
        this.mapper = mapper;
        this.tokens = tokens;
    }

    @Override
    public long allocate(AllocationCommand command) {
        return calls.execute(
                () -> {
                    var receipt = exchange("/api/rooms/allocate", command);
                    requireReceipt(receipt, command.bookingId(), "RESERVED");
                    requireRoom(receipt, null);
                    return receipt.roomId();
                },
                false);
    }

    @Override
    public void confirm(long roomId, ReservationCommand command) {
        calls.execute(
                () -> {
                    var receipt =
                            exchange("/api/rooms/" + roomId + "/confirm-availability", command);
                    requireReceipt(receipt, command.bookingId(), "RESERVED");
                    requireRoom(receipt, roomId);
                    return null;
                },
                false);
    }

    @Override
    public void release(ReleaseCommand command) {
        calls.execute(
                () -> {
                    var receipt = exchange("/api/rooms/release", command);
                    requireReceipt(receipt, command.bookingId(), "RELEASED");
                    return null;
                },
                true);
    }

    private ReservationReceipt exchange(String path, Object command) {
        try {
            var request =
                    HttpRequest.newBuilder(endpoints.resolve(path))
                            .timeout(properties.requestTimeout())
                            .header("Authorization", "Bearer " + tokens.serviceToken())
                            .header("Accept", "application/json")
                            .header("Content-Type", "application/json")
                            .header(RequestIds.HEADER, RequestIds.normalize(MDC.get("requestId")))
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            mapper.writeValueAsString(command)))
                            .build();
            var response = send(request);
            if (response.statusCode() >= 500 || response.statusCode() == 429) {
                throw new HotelUnavailableException(
                        "Hotel Service вернул " + response.statusCode());
            }
            if (response.statusCode() >= 400) throw rejected(response);
            return mapper.readValue(response.body(), ReservationReceipt.class);
        } catch (JsonProcessingException error) {
            throw new HotelUnavailableException("Hotel Service вернул некорректный JSON", error);
        }
    }

    private HttpResponse<String> send(HttpRequest request) {
        var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return pending.get(properties.requestTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            pending.cancel(true);
            throw new HotelUnavailableException("Истекло время ответа Hotel Service", error);
        } catch (InterruptedException error) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new HotelUnavailableException("Вызов Hotel Service прерван", error);
        } catch (ExecutionException error) {
            throw new HotelUnavailableException("Ошибка связи с Hotel Service", error.getCause());
        }
    }

    private HotelRejectedException rejected(HttpResponse<String> response) {
        String code = "HOTEL_REJECTED";
        String message = "Hotel Service отклонил запрос";
        try {
            var body = mapper.readTree(response.body());
            if (body != null) {
                code = body.path("code").asText(code);
                message = body.path("message").asText(message);
            }
        } catch (JsonProcessingException ignored) {
            // Статус 4xx остаётся окончательным отказом даже без JSON-тела.
        }
        return new HotelRejectedException(response.statusCode(), code, message);
    }

    private void requireReceipt(ReservationReceipt receipt, UUID bookingId, String status) {
        if (receipt == null
                || !bookingId.equals(receipt.bookingId())
                || !status.equals(receipt.status())) {
            throw new HotelUnavailableException("Hotel Service вернул некорректное подтверждение");
        }
    }

    private void requireRoom(ReservationReceipt receipt, Long expectedRoom) {
        if (receipt.roomId() == null
                || receipt.roomId() <= 0
                || (expectedRoom != null && !expectedRoom.equals(receipt.roomId()))) {
            throw new HotelUnavailableException("Hotel Service вернул другой номер");
        }
    }
}
