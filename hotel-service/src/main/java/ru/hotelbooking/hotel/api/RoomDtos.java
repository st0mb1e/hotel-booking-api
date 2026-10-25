package ru.hotelbooking.hotel.api;

import jakarta.validation.constraints.*;
import ru.hotelbooking.common.contract.RoomSummary;
import ru.hotelbooking.hotel.domain.Room;

public final class RoomDtos {
    private RoomDtos() {}

    public record RoomInput(
            @NotNull @Positive Long hotelId,
            @NotBlank @Size(max = 30) String number,
            boolean available) {}

    public record RoomUpdate(@NotBlank @Size(max = 30) String number, boolean available) {}

    public static RoomSummary room(Room room) {
        return new RoomSummary(
                room.getId(),
                room.getHotel().getId(),
                room.getNumber(),
                room.isAvailable(),
                room.getTimesBooked());
    }
}
