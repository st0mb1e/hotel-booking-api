package ru.hotelbooking.common.contract;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ReleaseCommand(@NotNull UUID bookingId, @NotNull Reason reason) {
    public enum Reason {
        COMPENSATION,
        USER_CANCELLATION
    }
}
