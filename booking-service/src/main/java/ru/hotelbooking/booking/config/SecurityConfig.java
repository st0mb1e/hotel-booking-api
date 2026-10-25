package ru.hotelbooking.booking.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import ru.hotelbooking.common.security.JwtSupport;
import ru.hotelbooking.common.web.ServletSecurity;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    JwtDecoder jwtDecoder(
            @Value("${app.jwt.public-key}") Resource key,
            @Value("${app.jwt.issuer}") String issuer) {
        return ServletSecurity.decoder(key, issuer, Set.of(JwtSupport.USER_AUDIENCE));
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, ObjectMapper mapper) throws Exception {
        return ServletSecurity.configure(
                http,
                mapper,
                "/api/auth/register",
                "/api/auth/login",
                "/user/register",
                "/user/auth");
    }
}
