package ru.hotelbooking.booking.service;

import java.time.Duration;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.hotelbooking.common.web.RequestIds;

@Component
@ConditionalOnProperty(
        name = "booking.recovery-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class BookingRecovery {
    private static final Logger log = LoggerFactory.getLogger(BookingRecovery.class);
    private final BookingTransactions transactions;
    private final CompensationService compensations;
    private final Duration pendingTimeout;

    public BookingRecovery(
            BookingTransactions transactions,
            CompensationService compensations,
            @Value("${booking.pending-timeout:120s}") Duration pendingTimeout) {
        this.transactions = transactions;
        this.compensations = compensations;
        this.pendingTimeout = pendingTimeout;
    }

    @Scheduled(fixedDelayString = "${booking.recovery-delay:5000}")
    public void recover() {
        try (var ignored = MDC.putCloseable("requestId", RequestIds.normalize(null))) {
            for (var id : transactions.stale(pendingTimeout)) {
                try {
                    transactions.cancelStale(id, pendingTimeout);
                } catch (RuntimeException error) {
                    log.atWarn()
                            .addKeyValue("event", "stale_booking_recovery_failed")
                            .addKeyValue("bookingId", id)
                            .setCause(error)
                            .log("stale_booking_recovery_failed");
                }
            }
            for (var id : transactions.compensationDue()) compensations.attempt(id);
        }
    }
}
