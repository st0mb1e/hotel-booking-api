package ru.hotelbooking.hotel.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import ru.hotelbooking.common.contract.ReleaseCommand;

@Entity
@Table(name = "room_reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoomReservation {
    public enum Status {
        RESERVED,
        RELEASED
    }

    @Id private UUID bookingId;

    private Long roomId;

    @Column(nullable = false)
    private boolean autoSelected;

    private Long hotelFilterId;

    @Version private Long version;

    private LocalDate startDate;
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private ReleaseCommand.Reason releaseReason;

    @Column(nullable = false)
    private Instant createdAt;

    public static RoomReservation reserve(
            UUID bookingId, Long roomId, LocalDate start, LocalDate end) {
        var reservation = new RoomReservation();
        reservation.bookingId = bookingId;
        reservation.roomId = roomId;
        reservation.startDate = start;
        reservation.endDate = end;
        reservation.status = Status.RESERVED;
        reservation.createdAt = Instant.now();
        return reservation;
    }

    public static RoomReservation allocate(
            UUID bookingId, long roomId, LocalDate start, LocalDate end, Long hotelId) {
        var reservation = reserve(bookingId, roomId, start, end);
        reservation.autoSelected = true;
        reservation.hotelFilterId = hotelId;
        return reservation;
    }

    public static RoomReservation released(
            UUID bookingId, Long roomId, ReleaseCommand.Reason reason) {
        var reservation = reserve(bookingId, roomId, null, null);
        reservation.release(reason);
        return reservation;
    }

    public void release(ReleaseCommand.Reason reason) {
        status = Status.RELEASED;
        releaseReason = reason;
    }
}
