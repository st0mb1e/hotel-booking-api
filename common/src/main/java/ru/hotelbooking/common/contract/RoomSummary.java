package ru.hotelbooking.common.contract;

public record RoomSummary(
        long id, long hotelId, String number, boolean available, long timesBooked) {}
