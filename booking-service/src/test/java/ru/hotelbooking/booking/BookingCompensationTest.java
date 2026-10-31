package ru.hotelbooking.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.hotelbooking.booking.api.BookingDtos.CreateBooking;
import ru.hotelbooking.booking.client.HotelRejectedException;
import ru.hotelbooking.booking.client.HotelUnavailableException;
import ru.hotelbooking.booking.domain.Booking;
import ru.hotelbooking.booking.service.BookingRecovery;
import ru.hotelbooking.common.contract.ReleaseCommand;

class BookingCompensationTest extends BookingIntegrationTestSupport {
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
        doThrow(new HotelUnavailableException("down")).when(hotel).release(any());
        create("deferred", input(1))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.details.compensationPending").value(true));
        var booking = bookings.findAll().getFirst();
        assertThat(booking.isCompensationPending()).isTrue();
        assertThat(booking.getCompensationAttempts()).isEqualTo(1);
        doNothing().when(hotel).release(any());
        jdbc.update(
                "update bookings set next_compensation_at = ? where id = ?",
                Instant.now().minusSeconds(1),
                booking.getId());
        new BookingRecovery(transactions, compensations, Duration.ofSeconds(120)).recover();
        assertThat(transactions.get(booking.getId()).compensationPending()).isFalse();
        verify(hotel, times(2)).release(any());
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
        verify(hotel).release(any());
    }

    @Test
    void noAvailableRoomsCancelsWithoutRelease() throws Exception {
        when(hotel.allocate(any()))
                .thenThrow(new HotelRejectedException(409, "ROOM_UNAVAILABLE", "occupied"));

        create("empty", new CreateBooking(null, true, null, start, end))
                .andExpect(status().isConflict());

        assertThat(bookings.findAll().getFirst().getStatus()).isEqualTo(Booking.Status.CANCELLED);
        verify(hotel, never()).release(any());
    }

    @Test
    void lostAllocationReplyIsCompensatedWithoutKnowingRoom() throws Exception {
        when(hotel.allocate(any())).thenThrow(new HotelUnavailableException("reply lost"));

        var response =
                create("unknown-room", new CreateBooking(null, true, null, start, end))
                        .andExpect(status().isServiceUnavailable())
                        .andReturn();
        var id = UUID.fromString(json(response).path("details").path("bookingId").asText());

        assertThat(transactions.get(id).roomId()).isNull();
        assertThat(transactions.get(id).status()).isEqualTo(Booking.Status.CANCELLED);
        verify(hotel)
                .release(
                        argThat(
                                command ->
                                        command.bookingId().equals(id)
                                                && command.reason()
                                                        == ReleaseCommand.Reason.COMPENSATION));
    }

    @Test
    void recoveryReleasesAnAllocationWhoseRoomWasNeverSaved() {
        var pending =
                transactions.begin(
                        alice.user().id(),
                        "unknown-stale",
                        new CreateBooking(null, true, null, start, end));
        jdbc.update(
                "update bookings set updated_at = ? where id = ?",
                Instant.now().minusSeconds(300),
                pending.booking().id());

        new BookingRecovery(transactions, compensations, Duration.ofSeconds(120)).recover();

        assertThat(transactions.get(pending.booking().id()).status())
                .isEqualTo(Booking.Status.CANCELLED);
        verify(hotel)
                .release(argThat(command -> command.bookingId().equals(pending.booking().id())));
    }

    @Test
    void definitiveMissingRoomDoesNotLeaveAnImpossibleCompensation() throws Exception {
        doThrow(new HotelRejectedException(404, "ROOM_NOT_FOUND", "missing"))
                .when(hotel)
                .confirm(anyLong(), any());
        create("missing-room", input(999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.details.compensationPending").value(false));
        verify(hotel, never()).release(any());
    }
}
