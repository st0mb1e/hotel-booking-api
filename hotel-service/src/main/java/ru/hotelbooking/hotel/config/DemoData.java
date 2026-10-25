package ru.hotelbooking.hotel.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.hotelbooking.hotel.domain.*;
import ru.hotelbooking.hotel.repository.*;

@Component
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
public class DemoData implements ApplicationRunner {
    private final HotelRepository hotels;
    private final RoomRepository rooms;

    public DemoData(HotelRepository hotels, RoomRepository rooms) {
        this.hotels = hotels;
        this.rooms = rooms;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (hotels.count() != 0) return;
        var first = hotels.save(new Hotel("Невский", "Санкт-Петербург, Невский проспект, 28"));
        var second = hotels.save(new Hotel("Волга", "Казань, улица Баумана, 15"));
        rooms.save(new Room(first, "101", true));
        rooms.save(new Room(first, "102", true));
        rooms.save(new Room(first, "103", false));
        rooms.save(new Room(second, "201", true));
        rooms.save(new Room(second, "202", true));
    }
}
