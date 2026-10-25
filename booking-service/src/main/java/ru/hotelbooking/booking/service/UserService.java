package ru.hotelbooking.booking.service;

import static ru.hotelbooking.booking.api.UserDtos.*;

import java.nio.charset.StandardCharsets;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.hotelbooking.booking.domain.User;
import ru.hotelbooking.booking.repository.UserRepository;
import ru.hotelbooking.common.api.*;

@Service
@Transactional(readOnly = true)
public class UserService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final TokenService tokens;

    public UserService(UserRepository users, PasswordEncoder passwords, TokenService tokens) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
    }

    @Transactional
    public TokenResponse register(Credentials input) {
        var user = create(input.username(), input.password(), User.Role.USER);
        return response(user);
    }

    public TokenResponse login(Credentials input) {
        var user =
                users.findByUsername(input.username())
                        .filter(User::isActive)
                        .orElseThrow(this::invalidCredentials);
        if (!passwords.matches(input.password(), user.getPassword())) throw invalidCredentials();
        return response(user);
    }

    public long currentId(Jwt jwt) {
        try {
            long id = Long.parseLong(jwt.getSubject());
            requireActive(id);
            return id;
        } catch (NumberFormatException e) {
            throw invalidCredentials();
        }
    }

    public User requireActive(long id) {
        return users.findById(id).filter(User::isActive).orElseThrow(this::invalidCredentials);
    }

    public PageResponse<UserView> list(int page, int size) {
        var result = users.findAll(PageRequest.of(page, size, Sort.by("id"))).map(UserView::from);
        return new PageResponse<>(
                result.getContent(), page, size, result.getTotalElements(), result.getTotalPages());
    }

    public UserView get(long id) {
        return UserView.from(requireUser(id));
    }

    @Transactional
    public UserView create(UserInput input) {
        return UserView.from(create(input.username(), input.password(), input.role()));
    }

    @Transactional
    public UserView update(long id, UserPatch input) {
        var user = requireUser(id);
        user.update(
                input.username(),
                input.password() == null ? null : encode(input.password()),
                input.role(),
                input.active());
        users.flush();
        return UserView.from(user);
    }

    @Transactional
    public void delete(long id) {
        requireUser(id).deactivate();
    }

    private User create(String username, String password, User.Role role) {
        if (users.findByUsername(username).isPresent())
            throw new ApiException(
                    HttpStatus.CONFLICT, "USERNAME_TAKEN", "Имя пользователя уже занято");
        return users.saveAndFlush(new User(username, encode(password), role));
    }

    private User requireUser(long id) {
        return users.findById(id)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        HttpStatus.NOT_FOUND,
                                        "USER_NOT_FOUND",
                                        "Пользователь не найден"));
    }

    private String encode(String password) {
        if (password.isBlank() || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_PASSWORD",
                    "Пароль не может быть пустым или превышать 72 байта UTF-8");
        return passwords.encode(password);
    }

    private TokenResponse response(User user) {
        return new TokenResponse(tokens.userToken(user), "Bearer", 3600, UserView.from(user));
    }

    private ApiException invalidCredentials() {
        return new ApiException(
                HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS",
                "Неверные учётные данные или пользователь отключён");
    }
}
