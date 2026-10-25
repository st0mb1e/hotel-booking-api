package ru.hotelbooking.hotel.api;

import java.time.LocalDate;
import java.util.List;

public final class StatisticsDtos {
    private StatisticsDtos() {}

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
}
