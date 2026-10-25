package ru.hotelbooking.common.contract;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.UUID;

public record AllocationCommand(
        @NotNull UUID bookingId,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        @Positive Long hotelId) {}
