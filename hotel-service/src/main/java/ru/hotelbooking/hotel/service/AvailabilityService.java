package ru.hotelbooking.hotel.service;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.hotelbooking.common.api.*;
import ru.hotelbooking.common.contract.*;
import ru.hotelbooking.hotel.domain.RoomReservation;
import ru.hotelbooking.hotel.repository.*;

@Service
public class AvailabilityService {
    private static final Logger log = LoggerFactory.getLogger(AvailabilityService.class);
    private final RoomRepository rooms;
    private final ReservationRepository reservations;

    public AvailabilityService(RoomRepository rooms, ReservationRepository reservations) {
        this.rooms = rooms;
        this.reservations = reservations;
    }

    @Transactional
    public ReservationReceipt confirm(long roomId, ReservationCommand command) {
        Dates.requireRange(command.startDate(), command.endDate());
        var room =
                rooms.lockById(roomId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                HttpStatus.NOT_FOUND,
                                                "ROOM_NOT_FOUND",
                                                "Номер не найден"));
        var existing = reservations.findById(command.bookingId());
        if (existing.isPresent()) {
            var saved = existing.get();
            if (saved.getStatus() == RoomReservation.Status.RELEASED) {
                throw conflict("RESERVATION_RELEASED", "Резервирование уже отменено");
            }
            if (saved.getRoomId() != roomId
                    || !Objects.equals(saved.getStartDate(), command.startDate())
                    || !Objects.equals(saved.getEndDate(), command.endDate())) {
                throw conflict(
                        "IDEMPOTENCY_CONFLICT",
                        "Идентификатор бронирования уже использован с другими параметрами");
            }
            return new ReservationReceipt(saved.getBookingId(), roomId, saved.getStatus().name());
        }
        if (!room.isAvailable()
                || room.isDeleted()
                || room.getHotel().isDeleted()
                || reservations.countOverlapping(roomId, command.startDate(), command.endDate())
                        > 0) {
            throw conflict("ROOM_UNAVAILABLE", "Номер недоступен на выбранные даты");
        }
        reservations.save(
                RoomReservation.reserve(
                        command.bookingId(), roomId, command.startDate(), command.endDate()));
        room.countReservation();
        log.atInfo()
                .addKeyValue("event", "room_reserved")
                .addKeyValue("bookingId", command.bookingId())
                .addKeyValue("roomId", roomId)
                .log("room_reserved");
        return new ReservationReceipt(command.bookingId(), roomId, "RESERVED");
    }

    @Transactional
    public ReservationReceipt release(long roomId, ReleaseCommand command) {
        var room =
                rooms.lockById(roomId)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                HttpStatus.NOT_FOUND,
                                                "ROOM_NOT_FOUND",
                                                "Номер не найден"));
        var existing = reservations.findById(command.bookingId());
        if (existing.isEmpty()) {
            reservations.save(
                    RoomReservation.released(command.bookingId(), roomId, command.reason()));
        } else {
            var reservation = existing.get();
            if (reservation.getRoomId() != roomId)
                throw conflict("IDEMPOTENCY_CONFLICT", "Бронирование относится к другому номеру");
            if (reservation.getStatus() == RoomReservation.Status.RESERVED) {
                reservation.release(command.reason());
                if (command.reason() == ReleaseCommand.Reason.COMPENSATION)
                    room.undoReservationCount();
            }
        }
        log.atInfo()
                .addKeyValue("event", "room_released")
                .addKeyValue("bookingId", command.bookingId())
                .addKeyValue("roomId", roomId)
                .addKeyValue("reason", command.reason())
                .log("room_released");
        return new ReservationReceipt(command.bookingId(), roomId, "RELEASED");
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
