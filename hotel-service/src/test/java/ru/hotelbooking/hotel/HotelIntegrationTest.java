package ru.hotelbooking.hotel;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static ru.hotelbooking.hotel.api.HotelDtos.*;
import static ru.hotelbooking.hotel.api.RoomDtos.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import ru.hotelbooking.common.api.ApiException;
import ru.hotelbooking.common.contract.*;
import ru.hotelbooking.common.testsupport.TestKeys;
import ru.hotelbooking.hotel.repository.*;
import ru.hotelbooking.hotel.service.*;

@SpringBootTest(
        properties = {
            "eureka.client.enabled=false",
            "spring.cloud.discovery.enabled=false",
            "app.demo.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:hotel-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
        })
@AutoConfigureMockMvc
class HotelIntegrationTest {
    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.public-key", () -> TestKeys.PUBLIC.toUri().toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired HotelCatalog catalog;
    @Autowired AvailabilityService availability;
    @Autowired StatisticsService statistics;
    @Autowired HotelRepository hotels;
    @Autowired RoomRepository rooms;
    @Autowired ReservationRepository reservations;
    long hotelId;
    long first;
    long second;
    long disabled;
    LocalDate start = LocalDate.now().plusDays(10);
    LocalDate end = start.plusDays(3);
    String user = TestKeys.user("1", "USER");
    String admin = TestKeys.user("2", "ADMIN");

    @BeforeEach
    void data() {
        reservations.deleteAll();
        rooms.deleteAll();
        hotels.deleteAll();
        hotelId = catalog.createHotel(new HotelInput("Тестовый отель", "Москва")).id();
        first = catalog.createRoom(new RoomInput(hotelId, "101", true)).id();
        second = catalog.createRoom(new RoomInput(hotelId, "102", true)).id();
        disabled = catalog.createRoom(new RoomInput(hotelId, "103", false)).id();
    }

    @Test
    void crudRequiresAdminAndValidatesInput() throws Exception {
        mvc.perform(get("/api/hotels"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        var body = mapper.writeValueAsString(new HotelInput("Новый", "Казань"));
        mvc.perform(
                        post("/api/hotels")
                                .header("Authorization", "Bearer " + user)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden());
        var response =
                mvc.perform(
                                post("/api/hotels")
                                        .header("Authorization", "Bearer " + admin)
                                        .header("X-Request-Id", "catalog-1")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andExpect(header().string("X-Request-Id", "catalog-1"))
                        .andReturn();
        long id = mapper.readTree(response.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(
                        patch("/api/hotels/" + id)
                                .header("Authorization", "Bearer " + admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Обновлённый\",\"address\":\"Казань\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Обновлённый"));
        mvc.perform(delete("/api/hotels/" + id).header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/hotels/" + id).header("Authorization", "Bearer " + user))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/hotels")
                                .header("Authorization", "Bearer " + admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"\",\"address\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void calendarUsesHalfOpenIntervalsAndOperationalAvailability() {
        availability.confirm(first, new ReservationCommand(UUID.randomUUID(), start, end));
        assertThat(
                        catalog.available(start.plusDays(1), end.plusDays(1), null, false, 0, 100)
                                .content())
                .extracting(RoomSummary::id)
                .containsExactly(second);
        assertThat(catalog.available(end, end.plusDays(1), null, false, 0, 100).content())
                .extracting(RoomSummary::id)
                .containsExactly(first, second);
    }

    @Test
    void recommendationSortsByUsageThenId() {
        var id = UUID.randomUUID();
        availability.confirm(first, new ReservationCommand(id, start, end));
        availability.release(
                first, new ReleaseCommand(id, ReleaseCommand.Reason.USER_CANCELLATION));
        assertThat(catalog.available(start, end, null, true, 0, 100).content())
                .extracting(RoomSummary::id)
                .containsExactly(second, first);
    }

    @Test
    void confirmAndCompensationAreIdempotent() {
        var command = new ReservationCommand(UUID.randomUUID(), start, end);
        availability.confirm(first, command);
        availability.confirm(first, command);
        assertThat(rooms.findById(first).orElseThrow().getTimesBooked()).isEqualTo(1);
        availability.release(
                first, new ReleaseCommand(command.bookingId(), ReleaseCommand.Reason.COMPENSATION));
        availability.release(
                first, new ReleaseCommand(command.bookingId(), ReleaseCommand.Reason.COMPENSATION));
        assertThat(rooms.findById(first).orElseThrow().getTimesBooked()).isZero();
        assertThat(reservations.count()).isEqualTo(1);
        assertThat(catalog.available(start, end, null, false, 0, 20).content()).hasSize(2);
    }

    @Test
    void releaseBeforeConfirmPreventsLateReservation() {
        UUID id = UUID.randomUUID();
        availability.release(first, new ReleaseCommand(id, ReleaseCommand.Reason.COMPENSATION));
        assertThatThrownBy(
                        () -> availability.confirm(first, new ReservationCommand(id, start, end)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("RESERVATION_RELEASED"));
        assertThat(rooms.findById(first).orElseThrow().getTimesBooked()).isZero();
    }

    @Test
    void requestIdCannotBeReusedForOtherDates() {
        var command = new ReservationCommand(UUID.randomUUID(), start, end);
        availability.confirm(first, command);
        assertThatThrownBy(
                        () ->
                                availability.confirm(
                                        first,
                                        new ReservationCommand(
                                                command.bookingId(), start, end.plusDays(1))))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void parallelOverlappingRequestsHaveOneWinner() throws Exception {
        try (var executor = Executors.newFixedThreadPool(16)) {
            var gate = new CountDownLatch(1);
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 16; i++)
                futures.add(
                        executor.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        availability.confirm(
                                                first,
                                                new ReservationCommand(
                                                        UUID.randomUUID(), start, end));
                                        return true;
                                    } catch (ApiException e) {
                                        assertThat(e.status().value()).isEqualTo(409);
                                        return false;
                                    }
                                }));
            gate.countDown();
            int winners = 0;
            for (var future : futures) if (future.get(15, TimeUnit.SECONDS)) winners++;
            assertThat(winners).isEqualTo(1);
        }
        assertThat(reservations.count()).isEqualTo(1);
        assertThat(rooms.findById(first).orElseThrow().getTimesBooked()).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateConfirmCountsOnce() throws Exception {
        var command = new ReservationCommand(UUID.randomUUID(), start, end);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Callable<ReservationReceipt>>();
            for (int i = 0; i < 8; i++) tasks.add(() -> availability.confirm(first, command));
            for (var result : executor.invokeAll(tasks))
                assertThat(result.get().status()).isEqualTo("RESERVED");
        }
        assertThat(rooms.findById(first).orElseThrow().getTimesBooked()).isEqualTo(1);
        assertThat(reservations.count()).isEqualTo(1);
    }

    @Test
    void adjacentBookingsCanCoexist() {
        availability.confirm(first, new ReservationCommand(UUID.randomUUID(), start, end));
        availability.confirm(
                first, new ReservationCommand(UUID.randomUUID(), end, end.plusDays(2)));
        assertThat(reservations.count()).isEqualTo(2);
    }

    @Test
    void internalEndpointsRequireServiceToken() throws Exception {
        String body =
                mapper.writeValueAsString(new ReservationCommand(UUID.randomUUID(), start, end));
        for (String token : List.of(user, admin))
            mvc.perform(
                            post("/api/rooms/" + first + "/confirm-availability")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/rooms/" + first + "/confirm-availability")
                                .header("Authorization", "Bearer " + TestKeys.service())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk());
        mvc.perform(get("/api/hotels").header("Authorization", "Bearer " + TestKeys.service()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/rooms/recommend")
                                .param("startDate", start.toString())
                                .param("endDate", end.toString())
                                .header("Authorization", "Bearer " + TestKeys.service()))
                .andExpect(status().isOk());
    }

    @Test
    void invalidAudienceAndExpiredTokensAreRejected() throws Exception {
        for (String token :
                List.of(
                        TestKeys.token(
                                "1",
                                List.of("USER"),
                                "other",
                                null,
                                Instant.now().plusSeconds(300)),
                        TestKeys.token(
                                "1",
                                List.of("USER"),
                                "hotel-booking-api",
                                null,
                                Instant.now().minusSeconds(120))))
            mvc.perform(get("/api/hotels").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
    }

    @Test
    void statisticsClipNightsToRequestedPeriod() {
        availability.confirm(
                first,
                new ReservationCommand(UUID.randomUUID(), start.minusDays(1), start.plusDays(2)));
        var report = statistics.report(start, start.plusDays(4));
        assertThat(report.occupiedNights()).isEqualTo(2);
        assertThat(report.totalRooms()).isEqualTo(3);
        assertThat(report.rooms().getFirst().occupancyPercent()).isEqualTo(50.0);
    }

    @Test
    void activeReservationPreventsDeletingOrDisablingRoom() {
        availability.confirm(first, new ReservationCommand(UUID.randomUUID(), start, end));
        assertThatThrownBy(() -> catalog.deleteRoom(first)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> catalog.deleteHotel(hotelId)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> catalog.updateRoom(first, new RoomUpdate("101", false)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void malformedDatesAndPaginationReturn400() throws Exception {
        mvc.perform(
                        get("/api/rooms")
                                .param("startDate", "bad")
                                .param("endDate", end.toString())
                                .header("Authorization", "Bearer " + user))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/hotels").param("size", "0").header("Authorization", "Bearer " + user))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/api/rooms")
                                .param("startDate", end.toString())
                                .param("endDate", start.toString())
                                .header("Authorization", "Bearer " + user))
                .andExpect(status().isBadRequest());
    }

    @Test
    void roomCrudAndDuplicateNumbers() throws Exception {
        String body = mapper.writeValueAsString(new RoomInput(hotelId, "204", true));
        var result =
                mvc.perform(
                                post("/api/rooms")
                                        .header("Authorization", "Bearer " + admin)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn();
        long id = mapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();
        mvc.perform(
                        post("/api/rooms")
                                .header("Authorization", "Bearer " + admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isConflict());
        mvc.perform(
                        patch("/api/rooms/" + id)
                                .header("Authorization", "Bearer " + admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"number\":\"205\",\"available\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));
        mvc.perform(delete("/api/rooms/" + id).header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/rooms/" + id).header("Authorization", "Bearer " + user))
                .andExpect(status().isNotFound());
    }

    @Test
    void parallelAllocationsDistributeNonOverlappingDatesEvenly() throws Exception {
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(32)) {
            var futures = new ArrayList<Future<ReservationReceipt>>();
            for (int i = 0; i < 32; i++) {
                var day = start.plusDays(i * 2L);
                futures.add(
                        executor.submit(
                                () -> {
                                    gate.await();
                                    return availability.allocate(
                                            new AllocationCommand(
                                                    UUID.randomUUID(),
                                                    day,
                                                    day.plusDays(1),
                                                    hotelId));
                                }));
            }
            gate.countDown();
            for (var future : futures)
                assertThat(future.get(15, TimeUnit.SECONDS).status()).isEqualTo("RESERVED");
        }
        assertThat(rooms.findById(first).orElseThrow().getTimesBooked()).isEqualTo(16);
        assertThat(rooms.findById(second).orElseThrow().getTimesBooked()).isEqualTo(16);
        assertThat(rooms.findById(disabled).orElseThrow().getTimesBooked()).isZero();
    }

    @Test
    void concurrentRequestsCannotReuseBookingIdForDifferentRooms() throws Exception {
        var command = new ReservationCommand(UUID.randomUUID(), start, end);
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = new ArrayList<Future<Boolean>>();
            for (long room : List.of(first, second))
                tasks.add(
                        executor.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        availability.confirm(room, command);
                                        return true;
                                    } catch (ApiException conflict) {
                                        assertThat(conflict.code())
                                                .isEqualTo("IDEMPOTENCY_CONFLICT");
                                        return false;
                                    }
                                }));
            gate.countDown();
            int winners = 0;
            for (var task : tasks) if (task.get(10, TimeUnit.SECONDS)) winners++;
            assertThat(winners).isEqualTo(1);
        }
        assertThat(reservations.count()).isEqualTo(1);
        assertThat(rooms.findAll().stream().mapToLong(room -> room.getTimesBooked()).sum())
                .isEqualTo(1);
    }

    @Test
    void concurrentDuplicateAllocationsSelectOneRoomAndCountOnce() throws Exception {
        var command = new AllocationCommand(UUID.randomUUID(), start, end, hotelId);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Callable<ReservationReceipt>>();
            for (int i = 0; i < 8; i++) tasks.add(() -> availability.allocate(command));
            for (var future : executor.invokeAll(tasks)) {
                assertThat(future.get(10, TimeUnit.SECONDS).roomId()).isEqualTo(first);
            }
        }
        assertThat(reservations.count()).isEqualTo(1);
        assertThat(rooms.findById(first).orElseThrow().getTimesBooked()).isEqualTo(1);
        assertThat(rooms.findById(second).orElseThrow().getTimesBooked()).isZero();
    }

    @Test
    void releaseWithoutRoomPreventsAnyLateReservation() {
        var id = UUID.randomUUID();
        var released =
                availability.release(new ReleaseCommand(id, ReleaseCommand.Reason.COMPENSATION));
        assertThat(released.roomId()).isNull();

        assertThatThrownBy(() -> availability.allocate(new AllocationCommand(id, start, end, null)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("RESERVATION_RELEASED"));
        assertThatThrownBy(
                        () -> availability.confirm(first, new ReservationCommand(id, start, end)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("RESERVATION_RELEASED"));
        assertThat(rooms.findById(first).orElseThrow().getTimesBooked()).isZero();
    }

    @Test
    void lostAllocationCanBeReleasedByBookingIdAndUsageRestored() {
        var command = new AllocationCommand(UUID.randomUUID(), start, end, hotelId);
        var receipt = availability.allocate(command);

        availability.release(
                new ReleaseCommand(command.bookingId(), ReleaseCommand.Reason.COMPENSATION));
        availability.release(
                new ReleaseCommand(command.bookingId(), ReleaseCommand.Reason.COMPENSATION));

        assertThat(rooms.findById(receipt.roomId()).orElseThrow().getTimesBooked()).isZero();
        assertThat(catalog.available(start, end, hotelId, false, 0, 20).content()).hasSize(2);
    }

    @Test
    void allocationHonoursHotelAndRejectsChangedIdempotentPayload() {
        var other = catalog.createHotel(new HotelInput("Другой", "Казань")).id();
        long room = catalog.createRoom(new RoomInput(other, "301", true)).id();
        var command = new AllocationCommand(UUID.randomUUID(), start, end, other);
        assertThat(availability.allocate(command).roomId()).isEqualTo(room);

        assertThatThrownBy(
                        () ->
                                availability.allocate(
                                        new AllocationCommand(
                                                command.bookingId(), start, end, hotelId)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        assertThatThrownBy(
                        () ->
                                availability.allocate(
                                        new AllocationCommand(
                                                UUID.randomUUID(), start, end, other)))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("ROOM_UNAVAILABLE"));
    }

    @Test
    void allocationAndReleaseByBookingRequireServiceAuthority() throws Exception {
        var command = new AllocationCommand(UUID.randomUUID(), start, end, hotelId);
        for (String token : List.of(user, admin)) {
            mvc.perform(
                            post("/api/rooms/allocate")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(mapper.writeValueAsString(command)))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            post("/api/rooms/release")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            mapper.writeValueAsString(
                                                    new ReleaseCommand(
                                                            command.bookingId(),
                                                            ReleaseCommand.Reason.COMPENSATION))))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(
                        post("/api/rooms/allocate")
                                .header("Authorization", "Bearer " + TestKeys.service())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(command)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(first));
        mvc.perform(
                        post("/api/rooms/release")
                                .header("Authorization", "Bearer " + TestKeys.service())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsString(
                                                new ReleaseCommand(
                                                        command.bookingId(),
                                                        ReleaseCommand.Reason.COMPENSATION))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
    }

    @Test
    void duplicateUuidCannotOverwriteExistingReservationThroughRepository() {
        var command = new ReservationCommand(UUID.randomUUID(), start, end);
        availability.confirm(first, command);

        assertThatThrownBy(
                        () ->
                                reservations.saveAndFlush(
                                        ru.hotelbooking.hotel.domain.RoomReservation.reserve(
                                                command.bookingId(), second, start, end)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(reservations.findById(command.bookingId()).orElseThrow().getRoomId())
                .isEqualTo(first);
        assertThat(rooms.findById(first).orElseThrow().getTimesBooked()).isEqualTo(1);
        assertThat(rooms.findById(second).orElseThrow().getTimesBooked()).isZero();
    }
}
