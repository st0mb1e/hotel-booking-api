package ru.hotelbooking.booking.api;

import static ru.hotelbooking.booking.api.UserDtos.*;

import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import ru.hotelbooking.booking.service.UserService;

@RestController
@SecurityRequirements
public class AuthController {
    private final UserService users;

    public AuthController(UserService users) {
        this.users = users;
    }

    @PostMapping({"/api/auth/register", "/user/register"})
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody Credentials input) {
        return users.register(input);
    }

    @PostMapping({"/api/auth/login", "/user/auth"})
    public TokenResponse login(@Valid @RequestBody Credentials input) {
        return users.login(input);
    }
}
