package ru.hotelbooking.hotel;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import ru.hotelbooking.common.api.ApiException;
import ru.hotelbooking.common.contract.ReservationCommand;
import ru.hotelbooking.hotel.api.HotelDtos.HotelInput;
import ru.hotelbooking.hotel.api.RoomDtos.RoomInput;
import ru.hotelbooking.hotel.api.RoomDtos.RoomUpdate;

class HotelCatalogTest extends HotelIntegrationTestSupport {
    @Test
    void crudRequiresAdminAndValidatesInput() throws Exception {
        mvc.perform(get("/api/hotels"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        var body = mapper.writeValueAsString(new HotelInput("Новый", "Казань"));
        mvc.perform(
                        post("/api/hotels")
                                .header("Authorization", "Bearer " + user)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden());
        var response =
                mvc.perform(
                                post("/api/hotels")
                                        .header("Authorization", "Bearer " + admin)
                                        .header("X-Request-Id", "catalog-1")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andExpect(header().string("X-Request-Id", "catalog-1"))
                        .andReturn();
        long id = mapper.readTree(response.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(
                        patch("/api/hotels/" + id)
                                .header("Authorization", "Bearer " + admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Обновлённый\",\"address\":\"Казань\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Обновлённый"));
        mvc.perform(delete("/api/hotels/" + id).header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/hotels/" + id).header("Authorization", "Bearer " + user))
                .andExpect(status().isNotFound());
        mvc.perform(
                        post("/api/hotels")
                                .header("Authorization", "Bearer " + admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"\",\"address\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void statisticsClipNightsToRequestedPeriod() {
        availability.confirm(
                first,
                new ReservationCommand(UUID.randomUUID(), start.minusDays(1), start.plusDays(2)));
        var report = statistics.report(start, start.plusDays(4));
        assertThat(report.occupiedNights()).isEqualTo(2);
        assertThat(report.totalRooms()).isEqualTo(3);
        assertThat(report.rooms().getFirst().occupancyPercent()).isEqualTo(50.0);
    }

    @Test
    void activeReservationPreventsDeletingOrDisablingRoom() {
        availability.confirm(first, new ReservationCommand(UUID.randomUUID(), start, end));
        assertThatThrownBy(() -> catalog.deleteRoom(first)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> catalog.deleteHotel(hotelId)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> catalog.updateRoom(first, new RoomUpdate("101", false)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void malformedDatesAndPaginationReturn400() throws Exception {
        mvc.perform(
                        get("/api/rooms")
                                .param("startDate", "bad")
                                .param("endDate", end.toString())
                                .header("Authorization", "Bearer " + user))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/hotels").param("size", "0").header("Authorization", "Bearer " + user))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/api/rooms")
                                .param("startDate", end.toString())
                                .param("endDate", start.toString())
                                .header("Authorization", "Bearer " + user))
                .andExpect(status().isBadRequest());
    }

    @Test
    void roomCrudAndDuplicateNumbers() throws Exception {
        String body = mapper.writeValueAsString(new RoomInput(hotelId, "204", true));
        var result =
                mvc.perform(
                                post("/api/rooms")
                                        .header("Authorization", "Bearer " + admin)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn();
        long id = mapper.readTree(result.getResponse().getContentAsString()).path("id").asLong();
        mvc.perform(
                        post("/api/rooms")
                                .header("Authorization", "Bearer " + admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isConflict());
        mvc.perform(
                        patch("/api/rooms/" + id)
                                .header("Authorization", "Bearer " + admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"number\":\"205\",\"available\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));
        mvc.perform(delete("/api/rooms/" + id).header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/rooms/" + id).header("Authorization", "Bearer " + user))
                .andExpect(status().isNotFound());
    }
}
