package ru.hotelbooking.common.api;

import java.time.LocalDate;
import org.springframework.http.HttpStatus;

public final class Dates {
    private Dates() {}

    public static void requireRange(LocalDate start, LocalDate end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_DATES",
                    "Дата выезда должна быть позже даты заезда");
        }
        if (end.isAfter(start.plusYears(1))) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_DATES",
                    "Период проживания не может превышать год");
        }
    }
}
