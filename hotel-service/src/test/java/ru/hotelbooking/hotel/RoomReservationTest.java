package ru.hotelbooking.hotel;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import ru.hotelbooking.common.api.ApiException;
import ru.hotelbooking.common.contract.ReleaseCommand;
import ru.hotelbooking.common.contract.ReservationCommand;
import ru.hotelbooking.common.contract.ReservationReceipt;
import ru.hotelbooking.common.contract.RoomSummary;

class RoomReservationTest extends HotelIntegrationTestSupport {
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
