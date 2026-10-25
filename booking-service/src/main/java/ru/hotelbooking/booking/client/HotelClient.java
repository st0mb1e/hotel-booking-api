package ru.hotelbooking.booking.client;

import java.time.LocalDate;
import java.util.List;
import ru.hotelbooking.common.contract.*;

public interface HotelClient {
    List<RoomSummary> recommend(LocalDate start, LocalDate end, Long hotelId);

    void confirm(long roomId, ReservationCommand command);

    void release(long roomId, ReleaseCommand command);
}
