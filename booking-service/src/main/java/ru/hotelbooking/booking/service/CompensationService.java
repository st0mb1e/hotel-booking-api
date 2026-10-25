package ru.hotelbooking.booking.service;

import java.util.UUID;
import org.slf4j.*;
import org.springframework.stereotype.Service;
import ru.hotelbooking.booking.client.HotelClient;
import ru.hotelbooking.common.contract.ReleaseCommand;

@Service
public class CompensationService {
    private static final Logger log = LoggerFactory.getLogger(CompensationService.class);
    private final BookingTransactions transactions;
    private final HotelClient hotel;

    public CompensationService(BookingTransactions transactions, HotelClient hotel) {
        this.transactions = transactions;
        this.hotel = hotel;
    }

    public void attempt(UUID id) {
        var task = transactions.compensation(id);
        if (task.isEmpty()) return;
        try {
            hotel.release(task.get().roomId(), new ReleaseCommand(id, task.get().reason()));
            transactions.compensationDone(id);
        } catch (RuntimeException error) {
            transactions.compensationFailed(id);
            log.atWarn()
                    .addKeyValue("event", "compensation_deferred")
                    .addKeyValue("bookingId", id)
                    .addKeyValue("cause", error.toString())
                    .log("compensation_deferred");
        }
    }
}
