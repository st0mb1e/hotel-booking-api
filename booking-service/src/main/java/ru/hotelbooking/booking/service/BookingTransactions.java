package ru.hotelbooking.booking.service;

import static ru.hotelbooking.booking.api.BookingDtos.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.hotelbooking.booking.domain.Booking;
import ru.hotelbooking.booking.repository.BookingRepository;
import ru.hotelbooking.common.api.*;
import ru.hotelbooking.common.contract.ReleaseCommand;

@Service
@Transactional(readOnly = true)
public class BookingTransactions {
    private static final Logger log = LoggerFactory.getLogger(BookingTransactions.class);
    private final BookingRepository bookings;
    private final UserService users;
    private final Clock clock;

    public BookingTransactions(BookingRepository bookings, UserService users, Clock clock) {
        this.bookings = bookings;
        this.users = users;
        this.clock = clock;
    }

    public record Start(BookingView booking, boolean created) {}

    public record Compensation(UUID id, ReleaseCommand.Reason reason) {}

    @Transactional
    public Start begin(long userId, String key, CreateBooking input) {
        var previous = bookings.findByUserIdAndRequestKey(userId, key);
        if (previous.isPresent()) return existing(previous.get(), input);
        if (input.startDate().isBefore(LocalDate.now(clock))) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "INVALID_DATES", "Дата заезда не может быть в прошлом");
        }
        var booking =
                bookings.saveAndFlush(
                        new Booking(
                                users.requireActive(userId),
                                input.autoSelect() ? null : input.roomId(),
                                input.autoSelect() ? input.hotelId() : null,
                                input.autoSelect(),
                                input.startDate(),
                                input.endDate(),
                                key,
                                fingerprint(input),
                                clock.instant()));
        log.atInfo()
                .addKeyValue("event", "booking_created")
                .addKeyValue("bookingId", booking.getId())
                .addKeyValue("userId", userId)
                .addKeyValue("state", "PENDING")
                .log("booking_created");
        return new Start(BookingView.from(booking), true);
    }

    public Start replay(long userId, String key, CreateBooking input) {
        return existing(
                bookings.findByUserIdAndRequestKey(userId, key)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                HttpStatus.CONFLICT,
                                                "REQUEST_CONFLICT",
                                                "Повторите запрос с тем же ключом")),
                input);
    }

    private String fingerprint(CreateBooking input) {
        try {
            String canonical =
                    input.autoSelect()
                            + ":"
                            + (input.autoSelect() ? input.hotelId() : input.roomId())
                            + ":"
                            + input.startDate()
                            + ":"
                            + input.endDate();
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Start existing(Booking booking, CreateBooking input) {
        if (!booking.getFingerprint().equals(fingerprint(input)))
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "Ключ уже использован с другими параметрами");
        return new Start(BookingView.from(booking), false);
    }

    public BookingView getOwned(UUID id, long userId) {
        var booking = require(id);
        if (booking.getUser().getId() != userId) throw missing();
        return BookingView.from(booking);
    }

    public BookingView get(UUID id) {
        return BookingView.from(require(id));
    }

    public PageResponse<BookingView> history(long userId, int page, int size) {
        var result =
                bookings.findByUserId(
                                userId,
                                PageRequest.of(
                                        page,
                                        size,
                                        Sort.by("createdAt").descending().and(Sort.by("id"))))
                        .map(BookingView::from);
        return new PageResponse<>(
                result.getContent(), page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public boolean assign(UUID id, long roomId) {
        var booking = lock(id);
        if (booking.getStatus() != Booking.Status.PENDING) return false;
        booking.assign(roomId, clock.instant());
        return true;
    }

    @Transactional
    public boolean confirm(UUID id) {
        var booking = lock(id);
        if (booking.getStatus() != Booking.Status.PENDING) return false;
        booking.confirm(clock.instant());
        log.atInfo()
                .addKeyValue("event", "booking_confirmed")
                .addKeyValue("bookingId", id)
                .addKeyValue("state", "CONFIRMED")
                .log("booking_confirmed");
        return true;
    }

    @Transactional
    public void fail(UUID id, String code, boolean needsCompensation) {
        var booking = lock(id);
        if (booking.getStatus() != Booking.Status.PENDING) return;
        booking.cancel(
                code, ReleaseCommand.Reason.COMPENSATION, clock.instant(), needsCompensation);
        log.atWarn()
                .addKeyValue("event", "booking_cancelled")
                .addKeyValue("bookingId", id)
                .addKeyValue("state", "CANCELLED")
                .addKeyValue("reason", code)
                .log("booking_cancelled");
    }

    @Transactional
    public void cancelOwned(UUID id, long userId) {
        var booking = lock(id);
        if (booking.getUser().getId() != userId) throw missing();
        if (booking.getStatus() == Booking.Status.CANCELLED) return;
        booking.cancel(
                "BOOKING_CANCELLED", ReleaseCommand.Reason.USER_CANCELLATION, clock.instant());
        log.atInfo()
                .addKeyValue("event", "booking_cancelled")
                .addKeyValue("bookingId", id)
                .addKeyValue("state", "CANCELLED")
                .addKeyValue("reason", "USER_CANCELLATION")
                .log("booking_cancelled");
    }

    public Optional<Compensation> compensation(UUID id) {
        var booking = require(id);
        return booking.isCompensationPending()
                ? Optional.of(new Compensation(id, booking.getCancellationReason()))
                : Optional.empty();
    }

    @Transactional
    public void compensationDone(UUID id) {
        var booking = lock(id);
        if (!booking.isCompensationPending()) return;
        booking.compensationDone(clock.instant());
        log.atInfo()
                .addKeyValue("event", "compensation_complete")
                .addKeyValue("bookingId", id)
                .log("compensation_complete");
    }

    @Transactional
    public void compensationFailed(UUID id) {
        var booking = lock(id);
        if (booking.isCompensationPending()) booking.compensationFailed(clock.instant());
    }

    public List<UUID> compensationDue() {
        return bookings
                .findByCompensationPendingTrueAndNextCompensationAtLessThanEqual(
                        clock.instant(), PageRequest.of(0, 100))
                .stream()
                .map(Booking::getId)
                .toList();
    }

    public List<UUID> stale(Duration timeout) {
        return bookings
                .findByStatusAndUpdatedAtBefore(
                        Booking.Status.PENDING,
                        clock.instant().minus(timeout),
                        PageRequest.of(0, 100))
                .stream()
                .map(Booking::getId)
                .toList();
    }

    @Transactional
    public void cancelStale(UUID id, Duration timeout) {
        var booking = lock(id);
        if (booking.getStatus() == Booking.Status.PENDING
                && booking.getUpdatedAt().isBefore(clock.instant().minus(timeout))) {
            booking.cancel("BOOKING_TIMEOUT", ReleaseCommand.Reason.COMPENSATION, clock.instant());
            log.atWarn()
                    .addKeyValue("event", "stale_booking_cancelled")
                    .addKeyValue("bookingId", id)
                    .addKeyValue("state", "CANCELLED")
                    .log("stale_booking_cancelled");
        }
    }

    private Booking require(UUID id) {
        return bookings.findById(id).orElseThrow(this::missing);
    }

    private Booking lock(UUID id) {
        return bookings.lockById(id).orElseThrow(this::missing);
    }

    private ApiException missing() {
        return new ApiException(
                HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Бронирование не найдено");
    }
}
