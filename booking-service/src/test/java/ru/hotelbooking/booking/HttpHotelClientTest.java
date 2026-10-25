package ru.hotelbooking.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
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
    HttpClient http;
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
        http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build();
        when(tokens.serviceToken()).thenReturn("service-token");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        executor.shutdownNow();
        http.close();
    }

    HttpHotelClient client(int attempts, int minimumCalls, Duration timeout, boolean discover) {
        var properties =
                new HotelClientProperties(
                        discover ? "" : "http://127.0.0.1:" + server.getAddress().getPort(),
                        Duration.ofMillis(200),
                        timeout,
                        attempts,
                        Duration.ofMillis(5),
                        minimumCalls,
                        Duration.ofSeconds(30));
        return new HttpHotelClient(
                http,
                new HotelEndpointResolver(discovery, properties),
                new HotelCallPolicy(properties),
                properties,
                mapper,
                tokens);
    }

    void handle(HttpExchange exchange) throws IOException {
        int call = calls.incrementAndGet();
        String path = exchange.getRequestURI().getPath();
        try {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
                    .isEqualTo("Bearer service-token");
            assertThat(exchange.getRequestHeaders().getFirst("X-Request-Id")).isNotBlank();
            boolean releasing = path.endsWith("/release");
            var json = mapper.readTree(exchange.getRequestBody());
            UUID id = UUID.fromString(json.path("bookingId").asText());
            if (!releasing && "timeout".equals(mode.get())) Thread.sleep(500);
            int status = 200;
            String body;
            if (releasing) {
                body = mapper.writeValueAsString(new ReservationReceipt(id, null, "RELEASED"));
            } else if ("down".equals(mode.get()) || ("recover".equals(mode.get()) && call < 3)) {
                status = 503;
                body = "{}";
            } else if ("conflict".equals(mode.get())) {
                status = 409;
                body = "{\"code\":\"ROOM_UNAVAILABLE\",\"message\":\"occupied\"}";
            } else if ("plain-rejection".equals(mode.get())) {
                status = 404;
                body = "not found";
            } else {
                if ("invalid-receipt".equals(mode.get()) && call < 3) id = UUID.randomUUID();
                body = mapper.writeValueAsString(new ReservationReceipt(id, 1L, "RESERVED"));
            }
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().flush();
            if (!releasing && "body-timeout".equals(mode.get())) Thread.sleep(700);
            exchange.getResponseBody().write(bytes);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException disconnected) {
            // Клиент отменяет соединение, когда истекает тайм-аут.
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
                            error -> assertThat(error.code()).isEqualTo("ROOM_UNAVAILABLE"));
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
    void timeoutIncludesBodyAfterHeadersHaveArrived() {
        mode.set("body-timeout");
        var client = client(3, 20, Duration.ofMillis(150), false);
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
        client.release(new ReleaseCommand(command.bookingId(), ReleaseCommand.Reason.COMPENSATION));
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void allocationReturnsRoomSelectedByHotel() {
        long selected =
                client(1, 20, Duration.ofSeconds(1), false)
                        .allocate(
                                new AllocationCommand(
                                        command.bookingId(), start, start.plusDays(2), null));
        assertThat(selected).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void invalidReceiptsAreRetriedWithinTheOperationPolicy() {
        mode.set("invalid-receipt");
        client(3, 20, Duration.ofSeconds(1), false).confirm(1, command);
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void plainBusinessRejectionsAreNotRetried() {
        mode.set("plain-rejection");
        assertThatThrownBy(() -> client(3, 20, Duration.ofSeconds(1), false).confirm(1, command))
                .isInstanceOfSatisfying(
                        HotelRejectedException.class,
                        error -> assertThat(error.status()).isEqualTo(404));
        assertThat(calls.get()).isEqualTo(1);
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
