package ru.hotelbooking.booking.config;

import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.hotelbooking.booking.domain.User;
import ru.hotelbooking.booking.repository.UserRepository;

@Component
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
public class DemoData implements ApplicationRunner {
    private final UserRepository users;
    private final PasswordEncoder passwords;

    public DemoData(UserRepository users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() != 0) return;
        users.save(new User("admin", passwords.encode("Admin12345!"), User.Role.ADMIN));
        users.save(new User("alice", passwords.encode("Alice12345!"), User.Role.USER));
    }
}
