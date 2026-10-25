package ru.hotelbooking.hotel.service;

import static ru.hotelbooking.hotel.api.HotelDtos.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.hotelbooking.common.api.Dates;
import ru.hotelbooking.hotel.domain.RoomReservation;
import ru.hotelbooking.hotel.repository.*;

@Service
public class StatisticsService {
    private final RoomRepository rooms;
    private final ReservationRepository reservations;

    public StatisticsService(RoomRepository rooms, ReservationRepository reservations) {
        this.rooms = rooms;
        this.reservations = reservations;
    }

    @Transactional(readOnly = true)
    public OccupancyReport report(LocalDate start, LocalDate end) {
        Dates.requireRange(start, end);
        long nights = ChronoUnit.DAYS.between(start, end);
        var byRoom =
                reservations.overlapping(start, end).stream()
                        .collect(Collectors.groupingBy(RoomReservation::getRoomId));
        var rows = new ArrayList<RoomStatistics>();
        for (var room : rooms.findByDeletedFalseAndHotelDeletedFalseOrderById()) {
            var stays = byRoom.getOrDefault(room.getId(), java.util.List.of());
            long occupied =
                    stays.stream()
                            .mapToLong(
                                    s ->
                                            ChronoUnit.DAYS.between(
                                                    s.getStartDate().isBefore(start)
                                                            ? start
                                                            : s.getStartDate(),
                                                    s.getEndDate().isAfter(end)
                                                            ? end
                                                            : s.getEndDate()))
                            .sum();
            rows.add(
                    new RoomStatistics(
                            room.getId(),
                            room.getHotel().getId(),
                            room.getNumber(),
                            room.getTimesBooked(),
                            stays.size(),
                            occupied,
                            percent(occupied, nights)));
        }
        rows.sort(
                Comparator.comparingLong(RoomStatistics::occupiedNights)
                        .reversed()
                        .thenComparingLong(RoomStatistics::roomId));
        long occupied = rows.stream().mapToLong(RoomStatistics::occupiedNights).sum();
        return new OccupancyReport(
                start, end, rows.size(), occupied, percent(occupied, nights * rows.size()), rows);
    }

    private double percent(long occupied, long total) {
        return total == 0 ? 0 : Math.round(10000.0 * occupied / total) / 100.0;
    }
}
