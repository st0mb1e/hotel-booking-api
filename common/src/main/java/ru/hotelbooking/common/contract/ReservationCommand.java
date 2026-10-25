package ru.hotelbooking.common.contract;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

public record ReservationCommand(
        @NotNull UUID bookingId, @NotNull LocalDate startDate, @NotNull LocalDate endDate) {}
