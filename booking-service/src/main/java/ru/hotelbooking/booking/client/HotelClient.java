package ru.hotelbooking.booking.client;

import ru.hotelbooking.common.contract.AllocationCommand;
import ru.hotelbooking.common.contract.ReleaseCommand;
import ru.hotelbooking.common.contract.ReservationCommand;

public interface HotelClient {
    long allocate(AllocationCommand command);

    void confirm(long roomId, ReservationCommand command);

    void release(ReleaseCommand command);
}
