package ru.hotelbooking.hotel.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.hotelbooking.hotel.domain.RoomReservation;

public interface ReservationRepository extends JpaRepository<RoomReservation, UUID> {
    @Query(
            "select count(s) from RoomReservation s where s.roomId = :roomId and s.status = ru.hotelbooking.hotel.domain.RoomReservation.Status.RESERVED and s.startDate < :endDate and s.endDate > :startDate")
    long countOverlapping(long roomId, LocalDate startDate, LocalDate endDate);

    @Query(
            "select s from RoomReservation s where s.status = ru.hotelbooking.hotel.domain.RoomReservation.Status.RESERVED and s.startDate < :endDate and s.endDate > :startDate")
    List<RoomReservation> overlapping(LocalDate startDate, LocalDate endDate);

    boolean existsByRoomIdAndStatusAndEndDateAfter(
            long roomId, RoomReservation.Status status, LocalDate date);
}
