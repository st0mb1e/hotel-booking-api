package ru.hotelbooking.booking.service;

import static ru.hotelbooking.booking.api.BookingDtos.*;

import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import ru.hotelbooking.booking.client.*;
import ru.hotelbooking.booking.domain.Booking;
import ru.hotelbooking.common.api.*;
import ru.hotelbooking.common.contract.*;

@Service
public class BookingService {
    private final BookingTransactions transactions;
    private final HotelClient hotel;
    private final CompensationService compensations;

    public BookingService(
            BookingTransactions transactions,
            HotelClient hotel,
            CompensationService compensations) {
        this.transactions = transactions;
        this.hotel = hotel;
        this.compensations = compensations;
    }

    public CreationResult create(long userId, String key, CreateBooking input) {
        Dates.requireRange(input.startDate(), input.endDate());
        BookingTransactions.Start start;
        try {
            start = transactions.begin(userId, key, input);
        } catch (DataIntegrityViolationException race) {
            start = transactions.replay(userId, key, input);
        }
        if (!start.created()) return outcome(start.booking(), true);
        UUID id = start.booking().id();
        try {
            if (input.autoSelect()) {
                long roomId =
                        hotel.allocate(
                                new AllocationCommand(
                                        id, input.startDate(), input.endDate(), input.hotelId()));
                if (transactions.assign(id, roomId)) transactions.confirm(id);
            } else if (transactions.assign(id, input.roomId())) {
                hotel.confirm(
                        input.roomId(),
                        new ReservationCommand(id, input.startDate(), input.endDate()));
                transactions.confirm(id);
            }
        } catch (HotelRejectedException rejected) {
            transactions.fail(
                    id, rejected.status() == 404 ? "ROOM_NOT_FOUND" : "ROOM_UNAVAILABLE", false);
        } catch (HotelUnavailableException unavailable) {
            transactions.fail(id, "HOTEL_UNAVAILABLE", true);
        } catch (RuntimeException unexpected) {
            transactions.fail(id, "BOOKING_FAILED", true);
            throw unexpected;
        } finally {
            if (transactions.get(id).status() == Booking.Status.CANCELLED)
                compensations.attempt(id);
        }
        return outcome(transactions.get(id), false);
    }

    public BookingView cancel(UUID id, long userId) {
        transactions.cancelOwned(id, userId);
        compensations.attempt(id);
        return transactions.getOwned(id, userId);
    }

    private CreationResult outcome(BookingView booking, boolean replayed) {
        if (booking.status() == Booking.Status.CANCELLED) {
            String code =
                    booking.failureCode() == null ? "BOOKING_CANCELLED" : booking.failureCode();
            HttpStatus status =
                    switch (code) {
                        case "HOTEL_UNAVAILABLE", "BOOKING_TIMEOUT", "BOOKING_FAILED" ->
                                HttpStatus.SERVICE_UNAVAILABLE;
                        case "ROOM_NOT_FOUND" -> HttpStatus.NOT_FOUND;
                        default -> HttpStatus.CONFLICT;
                    };
            throw new ApiException(
                    status,
                    code,
                    "Бронирование отменено",
                    Map.of(
                            "bookingId",
                            booking.id(),
                            "compensationPending",
                            booking.compensationPending(),
                            "replayed",
                            replayed));
        }
        return new CreationResult(booking, replayed);
    }
}
