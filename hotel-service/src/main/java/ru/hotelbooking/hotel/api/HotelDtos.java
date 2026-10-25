package ru.hotelbooking.hotel.api;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import ru.hotelbooking.common.contract.RoomSummary;
import ru.hotelbooking.hotel.domain.Hotel;
import ru.hotelbooking.hotel.domain.Room;

public final class HotelDtos {
    private HotelDtos() {}

    public record HotelInput(
            @NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 300) String address) {}

    public record HotelView(long id, String name, String address) {
        public static HotelView from(Hotel h) {
            return new HotelView(h.getId(), h.getName(), h.getAddress());
        }
    }

    public record RoomInput(
            @NotNull @Positive Long hotelId,
            @NotBlank @Size(max = 30) String number,
            boolean available) {}

    public record RoomUpdate(@NotBlank @Size(max = 30) String number, boolean available) {}

    public record RoomStatistics(
            long roomId,
            long hotelId,
            String number,
            long timesBooked,
            long reservations,
            long occupiedNights,
            double occupancyPercent) {}

    public record OccupancyReport(
            LocalDate startDate,
            LocalDate endDate,
            long totalRooms,
            long occupiedNights,
            double occupancyPercent,
            List<RoomStatistics> rooms) {}

    public static RoomSummary room(Room r) {
        return new RoomSummary(
                r.getId(),
                r.getHotel().getId(),
                r.getNumber(),
                r.isAvailable(),
                r.getTimesBooked());
    }
}
