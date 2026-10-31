package ru.hotelbooking.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import ru.hotelbooking.booking.api.BookingDtos.CreateBooking;
import ru.hotelbooking.booking.domain.Booking;
import ru.hotelbooking.common.api.ApiException;
import ru.hotelbooking.common.contract.AllocationCommand;
import ru.hotelbooking.common.contract.ReleaseCommand;
import ru.hotelbooking.common.contract.ReservationCommand;

class BookingCancellationTest extends BookingIntegrationTestSupport {
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
    void cancellationWhileAllocationIsInFlightReleasesByIdBeforeRoomIsKnown() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var bookingId = new AtomicReference<UUID>();
        when(hotel.allocate(any()))
                .thenAnswer(
                        call -> {
                            bookingId.set(call.<AllocationCommand>getArgument(0).bookingId());
                            entered.countDown();
                            assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
                            return 1L;
                        });
        var input = new CreateBooking(null, true, null, start, end);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var creating =
                    executor.submit(() -> service.create(alice.user().id(), "cancel-auto", input));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                service.cancel(bookingId.get(), alice.user().id());
            } finally {
                finish.countDown();
            }
            assertThatThrownBy(() -> creating.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ApiException.class);
        }
        var saved = transactions.get(bookingId.get());
        assertThat(saved.status()).isEqualTo(Booking.Status.CANCELLED);
        assertThat(saved.roomId()).isNull();
        verify(hotel, times(1))
                .release(argThat(command -> command.bookingId().equals(bookingId.get())));
    }
}
