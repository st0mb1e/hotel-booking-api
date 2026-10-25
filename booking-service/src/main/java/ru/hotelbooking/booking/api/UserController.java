package ru.hotelbooking.booking.api;

import static ru.hotelbooking.booking.api.UserDtos.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.hotelbooking.booking.service.UserService;
import ru.hotelbooking.common.api.*;

@RestController
@RequestMapping({"/api/users", "/user"})
@PreAuthorize("hasRole('ADMIN')")
public class UserController {
    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public PageResponse<UserView> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return users.list(page, size);
    }

    @GetMapping("/{id}")
    public UserView get(@PathVariable @Positive long id) {
        return users.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserView create(@Valid @RequestBody UserInput input) {
        return users.create(input);
    }

    @PatchMapping("/{id}")
    public UserView update(@PathVariable @Positive long id, @Valid @RequestBody UserPatch input) {
        return users.update(id, input);
    }

    @PatchMapping
    public UserView update(@Valid @RequestBody UserPatch input) {
        if (input.id() == null)
            throw new ApiException(
                    HttpStatus.BAD_REQUEST, "USER_ID_REQUIRED", "Укажите id пользователя");
        return users.update(input.id(), input);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable @Positive long id) {
        users.delete(id);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteQuery(@RequestParam @Positive long id) {
        users.delete(id);
    }
}
