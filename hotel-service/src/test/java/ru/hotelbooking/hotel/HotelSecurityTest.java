package ru.hotelbooking.hotel;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import ru.hotelbooking.common.contract.AllocationCommand;
import ru.hotelbooking.common.contract.ReleaseCommand;
import ru.hotelbooking.common.contract.ReservationCommand;
import ru.hotelbooking.common.testsupport.TestKeys;

class HotelSecurityTest extends HotelIntegrationTestSupport {
    @Test
    void internalEndpointsRequireServiceToken() throws Exception {
        String body =
                mapper.writeValueAsString(new ReservationCommand(UUID.randomUUID(), start, end));
        for (String token : List.of(user, admin))
            mvc.perform(
                            post("/api/rooms/" + first + "/confirm-availability")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/rooms/" + first + "/confirm-availability")
                                .header("Authorization", "Bearer " + TestKeys.service())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk());
        mvc.perform(get("/api/hotels").header("Authorization", "Bearer " + TestKeys.service()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        get("/api/rooms/recommend")
                                .param("startDate", start.toString())
                                .param("endDate", end.toString())
                                .header("Authorization", "Bearer " + TestKeys.service()))
                .andExpect(status().isOk());
    }

    @Test
    void invalidAudienceAndExpiredTokensAreRejected() throws Exception {
        for (String token :
                List.of(
                        TestKeys.token(
                                "1",
                                List.of("USER"),
                                "other",
                                null,
                                Instant.now().plusSeconds(300)),
                        TestKeys.token(
                                "1",
                                List.of("USER"),
                                "hotel-booking-api",
                                null,
                                Instant.now().minusSeconds(120))))
            mvc.perform(get("/api/hotels").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
    }

    @Test
    void allocationAndReleaseByBookingRequireServiceAuthority() throws Exception {
        var command = new AllocationCommand(UUID.randomUUID(), start, end, hotelId);
        for (String token : List.of(user, admin)) {
            mvc.perform(
                            post("/api/rooms/allocate")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(mapper.writeValueAsString(command)))
                    .andExpect(status().isForbidden());
            mvc.perform(
                            post("/api/rooms/release")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            mapper.writeValueAsString(
                                                    new ReleaseCommand(
                                                            command.bookingId(),
                                                            ReleaseCommand.Reason.COMPENSATION))))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(
                        post("/api/rooms/allocate")
                                .header("Authorization", "Bearer " + TestKeys.service())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(mapper.writeValueAsString(command)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value(first));
        mvc.perform(
                        post("/api/rooms/release")
                                .header("Authorization", "Bearer " + TestKeys.service())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsString(
                                                new ReleaseCommand(
                                                        command.bookingId(),
                                                        ReleaseCommand.Reason.COMPENSATION))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));
    }
}
