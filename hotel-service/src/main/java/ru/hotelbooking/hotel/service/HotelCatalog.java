package ru.hotelbooking.hotel.service;

import static ru.hotelbooking.hotel.api.HotelDtos.*;
import static ru.hotelbooking.hotel.api.RoomDtos.*;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.hotelbooking.common.api.*;
import ru.hotelbooking.common.contract.RoomSummary;
import ru.hotelbooking.hotel.domain.*;
import ru.hotelbooking.hotel.repository.*;

@Service
@Transactional(readOnly = true)
public class HotelCatalog {
    private final HotelRepository hotels;
    private final RoomRepository rooms;
    private final ReservationRepository reservations;
    private final ReservationGuard guard;

    public HotelCatalog(
            HotelRepository hotels,
            RoomRepository rooms,
            ReservationRepository reservations,
            ReservationGuard guard) {
        this.hotels = hotels;
        this.rooms = rooms;
        this.reservations = reservations;
        this.guard = guard;
    }

    public PageResponse<HotelView> hotels(String query, int page, int size) {
        return page(
                hotels.search(query, PageRequest.of(page, size, Sort.by("id")))
                        .map(HotelView::from));
    }

    public HotelView hotel(long id) {
        return HotelView.from(requireHotel(id));
    }

    @Transactional
    public HotelView createHotel(HotelInput input) {
        guard.lock();
        return HotelView.from(hotels.save(new Hotel(input.name(), input.address())));
    }

    @Transactional
    public HotelView updateHotel(long id, HotelInput input) {
        guard.lock();
        var hotel = requireHotelForWrite(id);
        hotel.update(input.name(), input.address());
        return HotelView.from(hotel);
    }

    @Transactional
    public void deleteHotel(long id) {
        guard.lock();
        var hotel = requireHotelForWrite(id);
        var activeRooms =
                rooms.findByHotelIdAndDeletedFalseOrderById(id).stream()
                        .map(r -> lockRoom(r.getId()))
                        .toList();
        activeRooms.forEach(this::requireNoFutureReservation);
        activeRooms.forEach(Room::retire);
        hotel.retire();
    }

    @Transactional
    public RoomSummary createRoom(RoomInput input) {
        guard.lock();
        return room(
                rooms.save(
                        new Room(
                                requireHotelForWrite(input.hotelId()),
                                input.number(),
                                input.available())));
    }

    @Transactional
    public RoomSummary updateRoom(long id, RoomUpdate input) {
        guard.lock();
        var room = lockRoom(id);
        if (!input.available()) requireNoFutureReservation(room);
        room.update(input.number(), input.available());
        return room(room);
    }

    @Transactional
    public void deleteRoom(long id) {
        guard.lock();
        var room = lockRoom(id);
        requireNoFutureReservation(room);
        room.retire();
    }

    public RoomSummary roomById(long id) {
        return room(requireRoom(id));
    }

    public PageResponse<RoomSummary> available(
            LocalDate start, LocalDate end, Long hotelId, boolean recommend, int page, int size) {
        Dates.requireRange(start, end);
        var sort =
                recommend ? Sort.by("timesBooked").ascending().and(Sort.by("id")) : Sort.by("id");
        return page(
                rooms.available(start, end, hotelId, PageRequest.of(page, size, sort))
                        .map(ru.hotelbooking.hotel.api.RoomDtos::room));
    }

    public List<RoomSummary> allRooms() {
        return rooms.findByDeletedFalseAndHotelDeletedFalseOrderById().stream()
                .map(ru.hotelbooking.hotel.api.RoomDtos::room)
                .toList();
    }

    private Hotel requireHotelForWrite(long id) {
        return hotels.lockById(id)
                .filter(h -> !h.isDeleted())
                .orElseThrow(() -> missing("HOTEL_NOT_FOUND", "Отель не найден"));
    }

    Hotel requireHotel(long id) {
        return hotels.findById(id)
                .filter(h -> !h.isDeleted())
                .orElseThrow(() -> missing("HOTEL_NOT_FOUND", "Отель не найден"));
    }

    Room requireRoom(long id) {
        return rooms.findById(id)
                .filter(r -> !r.isDeleted() && !r.getHotel().isDeleted())
                .orElseThrow(() -> missing("ROOM_NOT_FOUND", "Номер не найден"));
    }

    Room lockRoom(long id) {
        return rooms.lockById(id)
                .filter(r -> !r.isDeleted() && !r.getHotel().isDeleted())
                .orElseThrow(() -> missing("ROOM_NOT_FOUND", "Номер не найден"));
    }

    private void requireNoFutureReservation(Room room) {
        if (reservations.existsByRoomIdAndStatusAndEndDateAfter(
                room.getId(), RoomReservation.Status.RESERVED, LocalDate.now())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "ROOM_HAS_RESERVATIONS",
                    "У номера есть действующие бронирования");
        }
    }

    private ApiException missing(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private static <T> PageResponse<T> page(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
