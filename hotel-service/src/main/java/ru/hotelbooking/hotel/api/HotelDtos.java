package ru.hotelbooking.hotel.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import ru.hotelbooking.hotel.domain.Hotel;

public final class HotelDtos {
    private HotelDtos() {}

    public record HotelInput(
            @NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 300) String address) {}

    public record HotelView(long id, String name, String address) {
        public static HotelView from(Hotel hotel) {
            return new HotelView(hotel.getId(), hotel.getName(), hotel.getAddress());
        }
    }
}
