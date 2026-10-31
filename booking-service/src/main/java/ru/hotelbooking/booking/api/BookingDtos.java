package ru.hotelbooking.booking.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import ru.hotelbooking.booking.domain.Booking;

public final class BookingDtos {
    private BookingDtos() {}

    public record CreateBooking(
            Long roomId,
            boolean autoSelect,
            @Positive Long hotelId,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate) {
        @JsonIgnore
        @AssertTrue(message = "Укажите roomId или включите autoSelect")
        public boolean isSelectionValid() {
            return autoSelect || (roomId != null && roomId > 0);
        }
    }

    public record BookingView(
            UUID id,
            long userId,
            Long roomId,
            Long hotelId,
            LocalDate startDate,
            LocalDate endDate,
            Booking.Status status,
            Instant createdAt,
            String failureCode,
            boolean compensationPending) {
        public static BookingView from(Booking booking) {
            return new BookingView(
                    booking.getId(),
                    booking.getUser().getId(),
                    booking.getRoomId(),
                    booking.getHotelId(),
                    booking.getStartDate(),
                    booking.getEndDate(),
                    booking.getStatus(),
                    booking.getCreatedAt(),
                    booking.getFailureCode(),
                    booking.isCompensationPending());
        }
    }

    public record CreationResult(BookingView booking, boolean replayed) {}
}
