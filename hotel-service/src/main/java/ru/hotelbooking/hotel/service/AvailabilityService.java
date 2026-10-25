package ru.hotelbooking.hotel.service;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.hotelbooking.common.api.ApiException;
import ru.hotelbooking.common.api.Dates;
import ru.hotelbooking.common.contract.*;
import ru.hotelbooking.hotel.domain.Room;
import ru.hotelbooking.hotel.domain.RoomReservation;
import ru.hotelbooking.hotel.repository.*;

@Service
@Transactional
public class AvailabilityService {
    private static final Logger log = LoggerFactory.getLogger(AvailabilityService.class);
    private final RoomRepository rooms;
    private final ReservationRepository reservations;
    private final ReservationGuard guard;

    public AvailabilityService(
            RoomRepository rooms, ReservationRepository reservations, ReservationGuard guard) {
        this.rooms = rooms;
        this.reservations = reservations;
        this.guard = guard;
    }

    public ReservationReceipt confirm(long roomId, ReservationCommand command) {
        Dates.requireRange(command.startDate(), command.endDate());
        guard.lock();
        var existing = reservations.findById(command.bookingId());
        if (existing.isPresent()) {
            var saved = existing.get();
            requireActive(saved);
            requireSame(
                    !saved.isAutoSelected()
                            && Objects.equals(saved.getRoomId(), roomId)
                            && saved.getStartDate().equals(command.startDate())
                            && saved.getEndDate().equals(command.endDate()));
            return receipt(saved);
        }
        var room = lockRoom(roomId);
        if (!room.isAvailable()
                || room.isDeleted()
                || room.getHotel().isDeleted()
                || reservations.countOverlapping(roomId, command.startDate(), command.endDate())
                        > 0) {
            throw unavailable();
        }
        return reserve(
                room,
                RoomReservation.reserve(
                        command.bookingId(), roomId, command.startDate(), command.endDate()));
    }

    public ReservationReceipt allocate(AllocationCommand command) {
        Dates.requireRange(command.startDate(), command.endDate());
        guard.lock();
        var existing = reservations.findById(command.bookingId());
        if (existing.isPresent()) {
            var saved = existing.get();
            requireActive(saved);
            requireSame(
                    saved.isAutoSelected()
                            && Objects.equals(saved.getHotelFilterId(), command.hotelId())
                            && saved.getStartDate().equals(command.startDate())
                            && saved.getEndDate().equals(command.endDate()));
            return receipt(saved);
        }
        var candidates =
                rooms.available(
                        command.startDate(),
                        command.endDate(),
                        command.hotelId(),
                        PageRequest.of(0, 1, Sort.by("timesBooked").and(Sort.by("id"))));
        if (candidates.isEmpty()) throw unavailable();
        var room = lockRoom(candidates.getContent().getFirst().getId());
        return reserve(
                room,
                RoomReservation.allocate(
                        command.bookingId(),
                        room.getId(),
                        command.startDate(),
                        command.endDate(),
                        command.hotelId()));
    }

    public ReservationReceipt release(long roomId, ReleaseCommand command) {
        return releaseReservation(roomId, command);
    }

    public ReservationReceipt release(ReleaseCommand command) {
        return releaseReservation(null, command);
    }

    private ReservationReceipt releaseReservation(Long expectedRoomId, ReleaseCommand command) {
        guard.lock();
        var existing = reservations.findById(command.bookingId());
        RoomReservation saved;
        if (existing.isEmpty()) {
            if (expectedRoomId != null) lockRoom(expectedRoomId);
            saved =
                    reservations.save(
                            RoomReservation.released(
                                    command.bookingId(), expectedRoomId, command.reason()));
        } else {
            saved = existing.get();
            if (expectedRoomId != null && saved.getRoomId() != null) {
                requireSame(expectedRoomId.equals(saved.getRoomId()));
            }
            if (saved.getStatus() == RoomReservation.Status.RESERVED) {
                var room = lockRoom(saved.getRoomId());
                saved.release(command.reason());
                if (command.reason() == ReleaseCommand.Reason.COMPENSATION) {
                    room.undoReservationCount();
                }
            }
        }
        log.atInfo()
                .addKeyValue("event", "room_released")
                .addKeyValue("bookingId", command.bookingId())
                .addKeyValue("roomId", saved.getRoomId())
                .addKeyValue("reason", command.reason())
                .log("room_released");
        return receipt(saved);
    }

    private ReservationReceipt reserve(Room room, RoomReservation reservation) {
        reservations.saveAndFlush(reservation);
        room.countReservation();
        log.atInfo()
                .addKeyValue("event", "room_reserved")
                .addKeyValue("bookingId", reservation.getBookingId())
                .addKeyValue("roomId", room.getId())
                .log("room_reserved");
        return receipt(reservation);
    }

    private Room lockRoom(long id) {
        return rooms.lockById(id)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "Номер не найден"));
    }

    private void requireActive(RoomReservation reservation) {
        if (reservation.getStatus() == RoomReservation.Status.RELEASED) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "RESERVATION_RELEASED", "Резервирование уже отменено");
        }
    }

    private void requireSame(boolean matches) {
        if (!matches)
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "Идентификатор бронирования уже использован с другими параметрами");
    }

    private ApiException unavailable() {
        return new ApiException(
                HttpStatus.CONFLICT, "ROOM_UNAVAILABLE", "Номер недоступен на выбранные даты");
    }

    private ReservationReceipt receipt(RoomReservation reservation) {
        return new ReservationReceipt(
                reservation.getBookingId(),
                reservation.getRoomId(),
                reservation.getStatus().name());
    }
}
