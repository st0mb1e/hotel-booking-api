package ru.hotelbooking.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import ru.hotelbooking.booking.api.BookingDtos.CreateBooking;
import ru.hotelbooking.booking.api.BookingDtos.CreationResult;

class BookingIdempotencyTest extends BookingIntegrationTestSupport {
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
    void replayRemainsIdempotentAfterArrivalDateHasPassed() throws Exception {
        var arrival = LocalDate.now(ZoneOffset.UTC);
        var input = new CreateBooking(1L, false, null, arrival, arrival.plusDays(2));
        create("old-request", input).andExpect(status().isCreated());
        when(clock.instant()).thenReturn(Instant.now().plus(Duration.ofDays(2)));
        create("old-request", input).andExpect(status().isOk());
        verify(hotel, times(1)).confirm(anyLong(), any());
    }
}
