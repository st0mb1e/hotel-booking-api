package ru.hotelbooking.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false"})
class GatewayIntegrationTest {
    static final HttpServer BACKEND;

    static {
        try {
            BACKEND = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            BACKEND.createContext(
                    "/",
                    exchange -> {
                        var mapper = new ObjectMapper();
                        String auth = exchange.getRequestHeaders().getFirst("Authorization");
                        String trace = exchange.getRequestHeaders().getFirst("X-Request-Id");
                        byte[] body =
                                mapper.writeValueAsBytes(
                                        Map.of(
                                                "path",
                                                exchange.getRequestURI().getPath(),
                                                "authorization",
                                                auth == null ? "" : auth,
                                                "requestId",
                                                trace == null ? "" : trace));
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                        exchange.close();
                    });
            BACKEND.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.public-key", () -> TestKeys.PUBLIC.toUri().toString());
        registry.add(
                "gateway.hotel-uri", () -> "http://127.0.0.1:" + BACKEND.getAddress().getPort());
        registry.add(
                "gateway.booking-uri", () -> "http://127.0.0.1:" + BACKEND.getAddress().getPort());
    }

    @LocalServerPort int port;
    WebTestClient client;

    @BeforeEach
    void client() {
        client = WebTestClient.bindToServer().baseUrl("http://127.0.0.1:" + port).build();
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.stop(0);
    }

    @Test
    void protectsPrivateRoutes() {
        client.get()
                .uri("/api/bookings")
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("UNAUTHORIZED");
    }

    @Test
    void forwardsJwtAndCorrelationIdToBackend() {
        String token = TestKeys.user("1", "USER");
        client.get()
                .uri("/api/bookings")
                .headers(h -> h.setBearerAuth(token))
                .header("X-Request-Id", "gateway-123")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("X-Request-Id", "gateway-123")
                .expectBody()
                .jsonPath("$.authorization")
                .isEqualTo("Bearer " + token)
                .jsonPath("$.requestId")
                .isEqualTo("gateway-123");
    }

    @Test
    void authRoutesArePublicAndHotelAndLegacyRoutesAreForwarded() {
        client.post().uri("/api/auth/login").exchange().expectStatus().isOk();
        client.post().uri("/user/register").exchange().expectStatus().isOk();
        for (String path :
                new String[] {"/api/hotels", "/api/rooms/recommend", "/booking", "/bookings"}) {
            client.get()
                    .uri(path)
                    .headers(h -> h.setBearerAuth(TestKeys.user("1", "USER")))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.path")
                    .isEqualTo(path);
        }
    }

    @Test
    void internalRoutesAreBlockedEvenForAdministrators() {
        for (String operation : new String[] {"confirm-availability", "release"}) {
            client.post()
                    .uri("/api/rooms/1/" + operation)
                    .headers(h -> h.setBearerAuth(TestKeys.user("2", "ADMIN")))
                    .exchange()
                    .expectStatus()
                    .isForbidden();
        }
    }

    @Test
    void serviceTokensAndMalformedTokensAreRejectedAtPublicGateway() {
        for (String token : new String[] {TestKeys.service(), "broken-token"})
            client.get()
                    .uri("/api/hotels")
                    .headers(h -> h.setBearerAuth(token))
                    .exchange()
                    .expectStatus()
                    .isUnauthorized();
    }

    @Test
    void invalidCorrelationIdIsReplaced() {
        client.get()
                .uri("/api/hotels")
                .headers(h -> h.setBearerAuth(TestKeys.user("1", "USER")))
                .header("X-Request-Id", "bad id!")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueMatches("X-Request-Id", "[a-f0-9-]{36}");
    }
}
