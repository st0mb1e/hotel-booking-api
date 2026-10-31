package ru.hotelbooking.booking;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import ru.hotelbooking.booking.api.BookingDtos.CreateBooking;
import ru.hotelbooking.booking.api.UserDtos.Credentials;
import ru.hotelbooking.booking.api.UserDtos.TokenResponse;
import ru.hotelbooking.booking.api.UserDtos.UserInput;
import ru.hotelbooking.booking.client.HotelClient;
import ru.hotelbooking.booking.domain.User;
import ru.hotelbooking.booking.repository.BookingRepository;
import ru.hotelbooking.booking.repository.UserRepository;
import ru.hotelbooking.booking.service.BookingService;
import ru.hotelbooking.booking.service.BookingTransactions;
import ru.hotelbooking.booking.service.CompensationService;
import ru.hotelbooking.booking.service.UserService;
import ru.hotelbooking.common.testsupport.TestKeys;

@SpringBootTest(
        properties = {
            "eureka.client.enabled=false",
            "spring.cloud.discovery.enabled=false",
            "app.demo.enabled=false",
            "booking.recovery-enabled=false",
            "spring.datasource.url=jdbc:h2:mem:booking-test;DB_CLOSE_DELAY=-1"
        })
@AutoConfigureMockMvc
abstract class BookingIntegrationTestSupport {
    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.public-key", () -> TestKeys.PUBLIC.toUri().toString());
        registry.add("app.jwt.private-key", () -> TestKeys.PRIVATE.toUri().toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired BookingRepository bookings;
    @Autowired UserRepository users;
    @Autowired BookingService service;
    @Autowired BookingTransactions transactions;
    @Autowired CompensationService compensations;
    @Autowired UserService userService;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean HotelClient hotel;
    @MockitoBean Clock clock;
    TokenResponse alice;
    TokenResponse bob;
    String admin;
    LocalDate start = LocalDate.now().plusDays(10);
    LocalDate end = start.plusDays(3);

    @BeforeEach
    void data() {
        when(clock.instant()).thenReturn(Instant.now());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        bookings.deleteAll();
        users.deleteAll();
        reset(hotel);
        alice = userService.register(new Credentials("alice", "Alice12345!"));
        bob = userService.register(new Credentials("bob", "Bob12345!"));
        userService.create(new UserInput("admin", "Admin12345!", User.Role.ADMIN));
        admin = userService.login(new Credentials("admin", "Admin12345!")).accessToken();
    }

    protected CreateBooking input(long room) {
        return new CreateBooking(room, false, null, start, end);
    }

    protected ResultActions create(String key, CreateBooking input) throws Exception {
        return mvc.perform(
                post("/api/bookings")
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(input)));
    }

    protected JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    protected UUID created(String key) throws Exception {
        return UUID.fromString(
                json(create(key, input(1)).andExpect(status().isCreated()).andReturn())
                        .path("id")
                        .asText());
    }
}
