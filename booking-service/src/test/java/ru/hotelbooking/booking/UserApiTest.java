package ru.hotelbooking.booking;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class UserApiTest extends BookingIntegrationTestSupport {
    @Test
    void registerAndLoginIssueOneHourUserToken() throws Exception {
        var result =
                mvc.perform(
                                post("/api/auth/register")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"username\":\"charlie\",\"password\":\"Charlie123!\"}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.expiresIn").value(3600))
                        .andExpect(jsonPath("$.user.role").value("USER"))
                        .andExpect(jsonPath("$.user.password").doesNotExist())
                        .andReturn();
        String token = json(result).path("accessToken").asText();
        mvc.perform(get("/api/bookings").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"charlie\",\"password\":\"incorrect\"}"))
                .andExpect(status().isUnauthorized());
        assertThat(users.findByUsername("charlie").orElseThrow().getPassword()).startsWith("$2");
    }

    @Test
    void publicRegistrationCannotSetRoleAndUsernameIsUnique() throws Exception {
        mvc.perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"username\":\"charlie\",\"password\":\"Charlie123!\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"alice\",\"password\":\"Alice12345!\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void userAdministrationRequiresAdminAndDisabledUserCannotBook() throws Exception {
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isForbidden());
        mvc.perform(
                        post("/api/users")
                                .header("Authorization", "Bearer " + admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"username\":\"newuser\",\"password\":\"Password123!\",\"role\":\"USER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.password").doesNotExist());
        mvc.perform(
                        patch("/api/users/" + bob.user().id())
                                .header("Authorization", "Bearer " + admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"robert\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("robert"));
        mvc.perform(
                        delete("/api/users/" + alice.user().id())
                                .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
        create("disabled", input(1)).andExpect(status().isUnauthorized());
    }
}
