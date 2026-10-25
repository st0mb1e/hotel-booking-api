package ru.hotelbooking.hotel.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import ru.hotelbooking.hotel.domain.Hotel;

public interface HotelRepository extends JpaRepository<Hotel, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Hotel h where h.id = :id")
    Optional<Hotel> lockById(long id);

    @Query(
            "select h from Hotel h where h.deleted = false and (:q is null or lower(h.name) like lower(concat('%', :q, '%')) or lower(h.address) like lower(concat('%', :q, '%')))")
    Page<Hotel> search(String q, Pageable pageable);
}
