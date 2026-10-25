package ru.hotelbooking.common.contract;

import java.util.UUID;

public record ReservationReceipt(UUID bookingId, long roomId, String status) {}
