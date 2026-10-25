package ru.hotelbooking.hotel.api;

import static ru.hotelbooking.hotel.api.HotelDtos.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.hotelbooking.common.api.PageResponse;
import ru.hotelbooking.common.contract.*;
import ru.hotelbooking.hotel.service.*;

@RestController
@RequestMapping("/api/rooms")
@PreAuthorize("hasAnyRole('USER','ADMIN')")
public class RoomController {
    private final HotelCatalog catalog;
    private final AvailabilityService availability;

    public RoomController(HotelCatalog catalog, AvailabilityService availability) {
        this.catalog = catalog;
        this.availability = availability;
    }

    @GetMapping
    public PageResponse<RoomSummary> list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) @Positive Long hotelId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return catalog.available(startDate, endDate, hotelId, false, page, size);
    }

    @GetMapping("/recommend")
    @PreAuthorize("hasAnyRole('USER','ADMIN') or hasAuthority('SCOPE_rooms:write')")
    public PageResponse<RoomSummary> recommend(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) @Positive Long hotelId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return catalog.available(startDate, endDate, hotelId, true, page, size);
    }

    @GetMapping("/all")
    @PreAuthorize("hasRole('ADMIN')")
    public List<RoomSummary> all() {
        return catalog.allRooms();
    }

    @GetMapping("/{id}")
    public RoomSummary get(@PathVariable @Positive long id) {
        return catalog.roomById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public RoomSummary create(@Valid @RequestBody RoomInput input) {
        return catalog.createRoom(input);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public RoomSummary update(
            @PathVariable @Positive long id, @Valid @RequestBody RoomUpdate input) {
        return catalog.updateRoom(id, input);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable @Positive long id) {
        catalog.deleteRoom(id);
    }

    @PostMapping("/{id}/confirm-availability")
    @PreAuthorize("hasAuthority('SCOPE_rooms:write')")
    public ReservationReceipt confirm(
            @PathVariable @Positive long id, @Valid @RequestBody ReservationCommand command) {
        return availability.confirm(id, command);
    }

    @PostMapping("/{id}/release")
    @PreAuthorize("hasAuthority('SCOPE_rooms:write')")
    public ReservationReceipt release(
            @PathVariable @Positive long id, @Valid @RequestBody ReleaseCommand command) {
        return availability.release(id, command);
    }
}
