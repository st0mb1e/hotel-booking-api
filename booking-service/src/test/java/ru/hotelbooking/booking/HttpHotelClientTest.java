package ru.hotelbooking.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import ru.hotelbooking.booking.client.*;
import ru.hotelbooking.booking.service.TokenService;
import ru.hotelbooking.common.contract.*;

class HttpHotelClientTest {
    HttpServer server;
    ExecutorService executor;
    AtomicInteger calls;
    AtomicReference<String> mode;
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    DiscoveryClient discovery = mock(DiscoveryClient.class);
    TokenService tokens = mock(TokenService.class);
    LocalDate start = LocalDate.now().plusDays(10);
    ReservationCommand command =
            new ReservationCommand(UUID.randomUUID(), start, start.plusDays(2));

    @BeforeEach
    void startServer() throws Exception {
        calls = new AtomicInteger();
        mode = new AtomicReference<>("success");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        when(tokens.serviceToken()).thenReturn("service-token");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
    }

    HttpHotelClient client(int attempts, int minimumCalls, Duration timeout, boolean discover) {
        return new HttpHotelClient(
                discovery,
                tokens,
                mapper,
                discover ? "" : "http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofMillis(200),
                timeout,
                attempts,
                Duration.ofMillis(5),
                minimumCalls,
                Duration.ofSeconds(30));
    }

    void handle(HttpExchange exchange) throws IOException {
        int call = calls.incrementAndGet();
        String path = exchange.getRequestURI().getPath();
        int status = 200;
        String body;
        try {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                    .isEqualTo("Bearer service-token");
            assertThat(exchange.getRequestHeaders().getFirst("X-Request-Id")).isNotBlank();
            if (path.endsWith("/release")) {
                var request = mapper.readValue(exchange.getRequestBody(), ReleaseCommand.class);
                body =
                        mapper.writeValueAsString(
                                new ReservationReceipt(request.bookingId(), 1, "RELEASED"));
            } else if ("timeout".equals(mode.get())) {
                Thread.sleep(350);
                body = "{}";
            } else if ("down".equals(mode.get()) || ("recover".equals(mode.get()) && call < 3)) {
                status = 503;
                body = "{\"code\":\"UNAVAILABLE\"}";
            } else if ("conflict".equals(mode.get())) {
                status = 409;
                body = "{\"code\":\"ROOM_UNAVAILABLE\",\"message\":\"occupied\"}";
            } else if (path.endsWith("/recommend")) {
                boolean second = exchange.getRequestURI().getQuery().contains("page=1");
                body =
                        second
                                ? "{\"content\":[{\"id\":2,\"hotelId\":1,\"number\":\"102\",\"available\":true,\"timesBooked\":0}],\"page\":1,\"size\":100,\"totalElements\":2,\"totalPages\":2}"
                                : "{\"content\":[{\"id\":1,\"hotelId\":1,\"number\":\"101\",\"available\":true,\"timesBooked\":1}],\"page\":0,\"size\":100,\"totalElements\":2,\"totalPages\":2}";
            } else {
                var request = mapper.readValue(exchange.getRequestBody(), ReservationCommand.class);
                body =
                        mapper.writeValueAsString(
                                new ReservationReceipt(request.bookingId(), 1, "RESERVED"));
            }
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    @Test
    void retriesTemporaryErrorsAndEventuallyConfirms() {
        mode.set("recover");
        client(3, 20, Duration.ofSeconds(1), false).confirm(1, command);
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void businessConflictsAreNotRetriedAndDoNotOpenCircuit() {
        mode.set("conflict");
        var client = client(3, 2, Duration.ofSeconds(1), false);
        for (int i = 0; i < 5; i++)
            assertThatThrownBy(() -> client.confirm(1, command))
                    .isInstanceOfSatisfying(
                            HotelRejectedException.class,
                            e -> assertThat(e.code()).isEqualTo("ROOM_UNAVAILABLE"));
        assertThat(calls.get()).isEqualTo(5);
    }

    @Test
    void timeoutsHaveBoundedRetries() {
        mode.set("timeout");
        var client = client(3, 20, Duration.ofMillis(100), false);
        long before = System.nanoTime();
        assertThatThrownBy(() -> client.confirm(1, command))
                .isInstanceOf(HotelUnavailableException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - before)).isLessThan(Duration.ofSeconds(2));
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void openOperationCircuitDoesNotBlockCompensation() {
        mode.set("down");
        var client = client(1, 2, Duration.ofSeconds(1), false);
        for (int i = 0; i < 3; i++)
            assertThatThrownBy(() -> client.confirm(1, command))
                    .isInstanceOf(HotelUnavailableException.class);
        assertThat(calls.get()).isEqualTo(2);
        client.release(
                1, new ReleaseCommand(command.bookingId(), ReleaseCommand.Reason.COMPENSATION));
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void recommendationsReadAllPagesAndSortByUsage() {
        var result =
                client(1, 20, Duration.ofSeconds(1), false)
                        .recommend(start, start.plusDays(2), null);
        assertThat(result).extracting(RoomSummary::id).containsExactly(2L, 1L);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void resolvesHotelThroughDiscoveryWhenNoUrlOverrideIsSet() {
        when(discovery.getInstances("hotel-service"))
                .thenReturn(
                        List.of(
                                new DefaultServiceInstance(
                                        "hotel-1",
                                        "hotel-service",
                                        "127.0.0.1",
                                        server.getAddress().getPort(),
                                        false)));
        client(1, 20, Duration.ofSeconds(1), true).confirm(1, command);
        verify(discovery).getInstances("hotel-service");
    }
}
