package ru.hotelbooking.booking;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import ru.hotelbooking.booking.api.BookingDtos.CreateBooking;

class BookingApiTest extends BookingIntegrationTestSupport {
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
    void automaticSelectionDelegatesAtomicAllocationAndIgnoresRoomId() throws Exception {
        when(hotel.allocate(any())).thenReturn(2L);
        var auto = new CreateBooking(999L, true, 5L, start, end);

        create("auto", auto)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomId").value(2));
        create("auto", new CreateBooking(-888L, true, 5L, start, end)).andExpect(status().isOk());

        verify(hotel, times(1))
                .allocate(
                        argThat(
                                command ->
                                        command.hotelId().equals(5L)
                                                && command.startDate().equals(start)
                                                && command.endDate().equals(end)));
        verify(hotel, never()).confirm(anyLong(), any());
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
}
