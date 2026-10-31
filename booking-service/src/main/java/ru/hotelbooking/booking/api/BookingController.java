package ru.hotelbooking.booking.api;

import static ru.hotelbooking.booking.api.BookingDtos.*;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import ru.hotelbooking.booking.domain.Booking;
import ru.hotelbooking.booking.service.*;
import ru.hotelbooking.common.api.PageResponse;

@RestController
@PreAuthorize("hasAnyRole('USER','ADMIN')")
public class BookingController {
    private final BookingService service;
    private final BookingTransactions transactions;
    private final UserService users;

    public BookingController(
            BookingService service, BookingTransactions transactions, UserService users) {
        this.service = service;
        this.transactions = transactions;
        this.users = users;
    }

    @PostMapping({"/api/bookings", "/booking"})
    @Operation(summary = "Создание бронирования с Idempotency-Key")
    public ResponseEntity<BookingView> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}")
                    String key,
            @Valid @RequestBody CreateBooking input) {
        var result = service.create(users.currentId(jwt), key, input);
        var status =
                result.booking().status() == Booking.Status.PENDING
                        ? HttpStatus.ACCEPTED
                        : result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .location(URI.create("/api/bookings/" + result.booking().id()))
                .header("Idempotency-Replayed", Boolean.toString(result.replayed()))
                .body(result.booking());
    }

    @GetMapping({"/api/bookings", "/bookings"})
    public PageResponse<BookingView> history(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return transactions.history(users.currentId(jwt), page, size);
    }

    @GetMapping({"/api/bookings/{id}", "/booking/{id}"})
    public BookingView get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return transactions.getOwned(id, users.currentId(jwt));
    }

    @DeleteMapping({"/api/bookings/{id}", "/booking/{id}"})
    public BookingView cancel(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return service.cancel(id, users.currentId(jwt));
    }
}
