package ru.hotelbooking.hotel.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import ru.hotelbooking.hotel.domain.Room;

public interface RoomRepository extends JpaRepository<Room, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Room r where r.id = :id")
    Optional<Room> lockById(long id);

    @Query(
            "select r from Room r where r.deleted = false and r.hotel.deleted = false and r.available = true and (:hotelId is null or r.hotel.id = :hotelId) and not exists (select s.bookingId from RoomReservation s where s.roomId = r.id and s.status = ru.hotelbooking.hotel.domain.RoomReservation.Status.RESERVED and s.startDate < :endDate and s.endDate > :startDate)")
    Page<Room> available(LocalDate startDate, LocalDate endDate, Long hotelId, Pageable pageable);

    List<Room> findByHotelIdAndDeletedFalseOrderById(long hotelId);

    List<Room> findByDeletedFalseAndHotelDeletedFalseOrderById();
}
