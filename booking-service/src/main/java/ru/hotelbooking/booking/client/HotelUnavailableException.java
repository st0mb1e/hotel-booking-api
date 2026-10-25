package ru.hotelbooking.booking.client;

public class HotelUnavailableException extends RuntimeException {
    public HotelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public HotelUnavailableException(String message) {
        super(message);
    }
}
