package ru.hotelbooking.booking.api;

import jakarta.validation.constraints.*;
import ru.hotelbooking.booking.domain.User;

public final class UserDtos {
    private UserDtos() {}

    public record Credentials(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{3,64}") String username,
            @NotBlank @Size(min = 8, max = 72) String password) {}

    public record UserInput(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{3,64}") String username,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotNull User.Role role) {}

    public record UserPatch(
            @Positive Long id,
            @Pattern(regexp = "[A-Za-z0-9._-]{3,64}") String username,
            @Size(min = 8, max = 72) String password,
            User.Role role,
            Boolean active) {}

    public record UserView(long id, String username, User.Role role, boolean active) {
        public static UserView from(User user) {
            return new UserView(user.getId(), user.getUsername(), user.getRole(), user.isActive());
        }
    }

    public record TokenResponse(
            String accessToken, String tokenType, long expiresIn, UserView user) {}
}
