package ru.hotelbooking.hotel;

import static org.assertj.core.api.Assertions.*;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import ru.hotelbooking.common.api.ApiException;
import ru.hotelbooking.common.contract.AllocationCommand;
import ru.hotelbooking.common.contract.ReleaseCommand;
import ru.hotelbooking.common.contract.ReservationCommand;
import ru.hotelbooking.common.contract.ReservationReceipt;
import ru.hotelbooking.common.contract.RoomSummary;
import ru.hotelbooking.hotel.api.HotelDtos.HotelInput;
import ru.hotelbooking.hotel.api.RoomDtos.RoomInput;

class RoomAllocationTest extends HotelIntegrationTestSupport {
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
}
