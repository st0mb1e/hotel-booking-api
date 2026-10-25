package ru.hotelbooking.hotel.api;

import static ru.hotelbooking.hotel.api.HotelDtos.*;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.hotelbooking.common.api.PageResponse;
import ru.hotelbooking.hotel.service.*;

@RestController
@RequestMapping("/api/hotels")
@PreAuthorize("hasAnyRole('USER','ADMIN')")
public class HotelController {
    private final HotelCatalog catalog;
    private final StatisticsService statistics;

    public HotelController(HotelCatalog catalog, StatisticsService statistics) {
        this.catalog = catalog;
        this.statistics = statistics;
    }

    @GetMapping
    @Operation(summary = "Список отелей с поиском по названию и адресу")
    public PageResponse<HotelView> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return catalog.hotels(q, page, size);
    }

    @GetMapping("/{id}")
    public HotelView get(@PathVariable @Positive long id) {
        return catalog.hotel(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public HotelView create(@Valid @RequestBody HotelInput input) {
        return catalog.createHotel(input);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public HotelView update(@PathVariable @Positive long id, @Valid @RequestBody HotelInput input) {
        return catalog.updateHotel(id, input);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable @Positive long id) {
        catalog.deleteHotel(id);
    }

    @GetMapping("/statistics")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Загруженность: занятые ночи и процент за указанный период")
    public OccupancyReport statistics(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return statistics.report(startDate, endDate);
    }
}
