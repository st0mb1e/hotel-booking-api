package ru.hotelbooking.booking.repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import ru.hotelbooking.booking.domain.Booking;

public interface BookingRepository extends JpaRepository<Booking, UUID> {
    Optional<Booking> findByUserIdAndRequestKey(long userId, String requestKey);

    Page<Booking> findByUserId(long userId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> lockById(UUID id);

    List<Booking> findByStatusAndUpdatedAtBefore(
            Booking.Status status, Instant cutoff, Pageable pageable);

    List<Booking> findByCompensationPendingTrueAndNextCompensationAtLessThanEqual(
            Instant now, Pageable pageable);
}
