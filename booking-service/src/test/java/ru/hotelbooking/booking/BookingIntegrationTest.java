package ru.hotelbooking.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static ru.hotelbooking.booking.api.BookingDtos.*;
import static ru.hotelbooking.booking.api.UserDtos.*;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import ru.hotelbooking.booking.client.*;
import ru.hotelbooking.booking.domain.*;
import ru.hotelbooking.booking.repository.*;
import ru.hotelbooking.booking.service.*;
import ru.hotelbooking.common.api.ApiException;
import ru.hotelbooking.common.contract.*;

@SpringBootTest(
        properties = {
            "eureka.client.enabled=false",
            "spring.cloud.discovery.enabled=false",
            "app.demo.enabled=false",
            "booking.recovery-enabled=false",
            "spring.datasource.url=jdbc:h2:mem:booking-test;DB_CLOSE_DELAY=-1"
        })
@AutoConfigureMockMvc
class BookingIntegrationTest {
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
        var adminUser = userService.create(new UserInput("admin", "Admin12345!", User.Role.ADMIN));
        admin = userService.login(new Credentials("admin", "Admin12345!")).accessToken();
    }

    private CreateBooking input(long room) {
        return new CreateBooking(room, false, null, start, end);
    }

    private ResultActions create(String key, CreateBooking input) throws Exception {
        return mvc.perform(
                post("/api/bookings")
                        .header("Authorization", "Bearer " + alice.accessToken())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(input)));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    private UUID created(String key) throws Exception {
        return UUID.fromString(
                json(create(key, input(1)).andExpect(status().isCreated()).andReturn())
                        .path("id")
                        .asText());
    }

    @Test
    void registerAndLoginIssueOneHourUserToken() throws Exception {
        var result =
                mvc.perform(
                                post("/api/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"username\":\"charlie\",\"password\":\"Charlie123!\"}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.expiresIn").value(3600))
                        .andExpect(jsonPath("$.user.role").value("USER"))
                        .andExpect(jsonPath("$.user.password").doesNotExist())
                        .andReturn();
        String token = json(result).path("accessToken").asText();
        mvc.perform(get("/api/bookings").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"charlie\",\"password\":\"incorrect\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(users.findByUsername("charlie").orElseThrow().getPassword()).startsWith("$2");
    }

    @Test
    void publicRegistrationCannotSetRoleAndUsernameIsUnique() throws Exception {
        mvc.perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"username\":\"charlie\",\"password\":\"Charlie123!\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"alice\",\"password\":\"Alice12345!\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void successfulBookingHasPrivatePaginatedHistory() throws Exception {
        UUID id = created("success");
        mvc.perform(
                        get("/api/bookings/" + id)
                                .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        mvc.perform(
                        get("/api/bookings/" + id)
                                .header("Authorization", "Bearer " + bob.accessToken()))
                .andExpect(status().isNotFound());
        mvc.perform(
                        get("/api/bookings")
                                .param("size", "1")
                                .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content.length()").value(1));
        mvc.perform(get("/api/bookings").header("Authorization", "Bearer " + bob.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        verify(hotel).confirm(eq(1L), argThat(command -> command.bookingId().equals(id)));
    }

    @Test
    void duplicateRequestReturnsSameBookingWithoutSecondRemoteCall() throws Exception {
        UUID id = created("duplicate");
        create("duplicate", input(1))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id.toString()));
        assertThat(bookings.count()).isEqualTo(1);
        verify(hotel, times(1)).confirm(eq(1L), any());
    }

    @Test
    void idempotencyKeyCannotChangePayload() throws Exception {
        created("same-key");
        create("same-key", input(2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void existingPendingRequestReturns202WithoutStartingAnotherSaga() throws Exception {
        var pending = transactions.begin(alice.user().id(), "pending", input(1));
        create("pending", input(1))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(pending.booking().id().toString()));
        verifyNoInteractions(hotel);
    }

    @Test
    void remoteFailureCancelsAndCompensates() throws Exception {
        doThrow(new HotelUnavailableException("timeout")).when(hotel).confirm(anyLong(), any());
        var response =
                create("timeout", input(1))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.code").value("HOTEL_UNAVAILABLE"))
                        .andReturn();
        var id = UUID.fromString(json(response).path("details").path("bookingId").asText());
        assertThat(transactions.get(id).status()).isEqualTo(Booking.Status.CANCELLED);
        assertThat(transactions.get(id).compensationPending()).isFalse();
        verify(hotel)
                .release(
                        eq(1L),
                        argThat(
                                command ->
                                        command.bookingId().equals(id)
                                                && command.reason()
                                                        == ReleaseCommand.Reason.COMPENSATION));
        create("timeout", input(1))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.details.replayed").value(true));
        verify(hotel, times(1)).confirm(anyLong(), any());
    }

    @Test
    void failedCompensationIsPersistedAndRetriedByRecovery() throws Exception {
        doThrow(new HotelUnavailableException("down")).when(hotel).confirm(anyLong(), any());
        doThrow(new HotelUnavailableException("down")).when(hotel).release(anyLong(), any());
        create("deferred", input(1))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.details.compensationPending").value(true));
        var booking = bookings.findAll().getFirst();
        assertThat(booking.isCompensationPending()).isTrue();
        assertThat(booking.getCompensationAttempts()).isEqualTo(1);
        doNothing().when(hotel).release(anyLong(), any());
        jdbc.update(
                "update bookings set next_compensation_at = ? where id = ?",
                Instant.now().minusSeconds(1),
                booking.getId());
        new BookingRecovery(transactions, compensations, Duration.ofSeconds(120)).recover();
        assertThat(transactions.get(booking.getId()).compensationPending()).isFalse();
        verify(hotel, times(2)).release(anyLong(), any());
    }

    @Test
    void stalePendingIsCancelledAndItsAssignedRoomReleased() {
        var pending = transactions.begin(alice.user().id(), "stale", input(1));
        jdbc.update(
                "update bookings set updated_at = ? where id = ?",
                Instant.now().minusSeconds(300),
                pending.booking().id());
        new BookingRecovery(transactions, compensations, Duration.ofSeconds(120)).recover();
        assertThat(transactions.get(pending.booking().id()).status())
                .isEqualTo(Booking.Status.CANCELLED);
        assertThat(transactions.get(pending.booking().id()).compensationPending()).isFalse();
        verify(hotel).release(eq(1L), any());
    }

    @Test
    void parallelDuplicateRequestsStartOnlyOneSaga() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var gate = new CountDownLatch(1);
            var futures = new ArrayList<Future<CreationResult>>();
            for (int i = 0; i < 8; i++)
                futures.add(
                        executor.submit(
                                () -> {
                                    gate.await();
                                    return service.create(alice.user().id(), "parallel", input(1));
                                }));
            gate.countDown();
            var ids = new HashSet<UUID>();
            for (var future : futures) ids.add(future.get(15, TimeUnit.SECONDS).booking().id());
            assertThat(ids).hasSize(1);
        }
        assertThat(bookings.count()).isEqualTo(1);
        verify(hotel, times(1)).confirm(anyLong(), any());
    }

    @Test
    void automaticSelectionTriesNextRoomOnConflictAndIgnoresRoomId() throws Exception {
        when(hotel.recommend(start, end, 5L))
                .thenReturn(
                        List.of(
                                new RoomSummary(1, 5, "101", true, 0),
                                new RoomSummary(2, 5, "102", true, 0)));
        doThrow(new HotelRejectedException(409, "ROOM_UNAVAILABLE", "occupied"))
                .when(hotel)
                .confirm(eq(1L), any());
        var auto = new CreateBooking(999L, true, 5L, start, end);
        create("auto", auto)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomId").value(2));
        create("auto", new CreateBooking(-888L, true, 5L, start, end)).andExpect(status().isOk());
        verify(hotel).confirm(eq(1L), any());
        verify(hotel).confirm(eq(2L), any());
    }

    @Test
    void noRecommendedRoomsCancelsWithoutRelease() throws Exception {
        when(hotel.recommend(start, end, null)).thenReturn(List.of());
        create("empty", new CreateBooking(null, true, null, start, end))
                .andExpect(status().isConflict());
        assertThat(bookings.findAll().getFirst().getStatus()).isEqualTo(Booking.Status.CANCELLED);
        verify(hotel, never()).release(anyLong(), any());
    }

    @Test
    void cancellationReleasesOnceAndCannotAffectAnotherUser() throws Exception {
        UUID id = created("cancel");
        mvc.perform(
                        delete("/api/bookings/" + id)
                                .header("Authorization", "Bearer " + bob.accessToken()))
                .andExpect(status().isNotFound());
        for (int i = 0; i < 2; i++)
            mvc.perform(
                            delete("/api/bookings/" + id)
                                    .header("Authorization", "Bearer " + alice.accessToken()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));
        verify(hotel, times(1))
                .release(
                        eq(1L),
                        argThat(
                                command ->
                                        command.reason()
                                                == ReleaseCommand.Reason.USER_CANCELLATION));
    }

    @Test
    void lateConfirmationCannotReviveCancelledBooking() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var id = new AtomicReference<UUID>();
        doAnswer(
                        call -> {
                            id.set(call.<ReservationCommand>getArgument(1).bookingId());
                            entered.countDown();
                            assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
                            return null;
                        })
                .when(hotel)
                .confirm(eq(1L), any());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future =
                    executor.submit(
                            () -> service.create(alice.user().id(), "race-cancel", input(1)));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                service.cancel(id.get(), alice.user().id());
            } finally {
                finish.countDown();
            }
            assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ApiException.class);
        }
        assertThat(transactions.get(id.get()).status()).isEqualTo(Booking.Status.CANCELLED);
    }

    @Test
    void invalidRequestsDoNotCreateBookings() throws Exception {
        create("dates", new CreateBooking(1L, false, null, end, start))
                .andExpect(status().isBadRequest());
        create("past", new CreateBooking(1L, false, null, LocalDate.now().minusDays(1), end))
                .andExpect(status().isBadRequest());
        create("no-room", new CreateBooking(null, false, null, start, end))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/bookings")
                                .header("Authorization", "Bearer " + alice.accessToken())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(input(1))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/bookings")).andExpect(status().isUnauthorized());
        assertThat(bookings.count()).isZero();
        verifyNoInteractions(hotel);
    }

    @Test
    void userAdministrationRequiresAdminAndDisabledUserCannotBook() throws Exception {
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/users")
                                .header("Authorization", "Bearer " + admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"username\":\"newuser\",\"password\":\"Password123!\",\"role\":\"USER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.password").doesNotExist());
        mvc.perform(
                        patch("/api/users/" + bob.user().id())
                                .header("Authorization", "Bearer " + admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"robert\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("robert"));
        mvc.perform(
                        delete("/api/users/" + alice.user().id())
                                .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
        create("disabled", input(1)).andExpect(status().isUnauthorized());
    }

    @Test
    void replayRemainsIdempotentAfterArrivalDateHasPassed() throws Exception {
        var arrival = LocalDate.now(ZoneOffset.UTC);
        var input = new CreateBooking(1L, false, null, arrival, arrival.plusDays(2));
        create("old-request", input).andExpect(status().isCreated());
        when(clock.instant()).thenReturn(Instant.now().plus(Duration.ofDays(2)));
        create("old-request", input).andExpect(status().isOk());
        verify(hotel, times(1)).confirm(anyLong(), any());
    }

    @Test
    void definitiveMissingRoomDoesNotLeaveAnImpossibleCompensation() throws Exception {
        doThrow(new HotelRejectedException(404, "ROOM_NOT_FOUND", "missing"))
                .when(hotel)
                .confirm(anyLong(), any());
        create("missing-room", input(999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details.compensationPending").value(false));
        verify(hotel, never()).release(anyLong(), any());
    }
}
