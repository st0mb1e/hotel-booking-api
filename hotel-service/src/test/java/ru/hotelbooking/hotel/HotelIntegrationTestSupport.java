package ru.hotelbooking.hotel;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import ru.hotelbooking.common.testsupport.TestKeys;
import ru.hotelbooking.hotel.api.HotelDtos.HotelInput;
import ru.hotelbooking.hotel.api.RoomDtos.RoomInput;
import ru.hotelbooking.hotel.repository.HotelRepository;
import ru.hotelbooking.hotel.repository.ReservationRepository;
import ru.hotelbooking.hotel.repository.RoomRepository;
import ru.hotelbooking.hotel.service.AvailabilityService;
import ru.hotelbooking.hotel.service.HotelCatalog;
import ru.hotelbooking.hotel.service.StatisticsService;

@SpringBootTest(
        properties = {
            "eureka.client.enabled=false",
            "spring.cloud.discovery.enabled=false",
            "app.demo.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:hotel-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
        })
@AutoConfigureMockMvc
abstract class HotelIntegrationTestSupport {
    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.public-key", () -> TestKeys.PUBLIC.toUri().toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired HotelCatalog catalog;
    @Autowired AvailabilityService availability;
    @Autowired StatisticsService statistics;
    @Autowired HotelRepository hotels;
    @Autowired RoomRepository rooms;
    @Autowired ReservationRepository reservations;
    long hotelId;
    long first;
    long second;
    long disabled;
    LocalDate start = LocalDate.now().plusDays(10);
    LocalDate end = start.plusDays(3);
    String user = TestKeys.user("1", "USER");
    String admin = TestKeys.user("2", "ADMIN");

    @BeforeEach
    void data() {
        reservations.deleteAll();
        rooms.deleteAll();
        hotels.deleteAll();
        hotelId = catalog.createHotel(new HotelInput("Тестовый отель", "Москва")).id();
        first = catalog.createRoom(new RoomInput(hotelId, "101", true)).id();
        second = catalog.createRoom(new RoomInput(hotelId, "102", true)).id();
        disabled = catalog.createRoom(new RoomInput(hotelId, "103", false)).id();
    }
}
