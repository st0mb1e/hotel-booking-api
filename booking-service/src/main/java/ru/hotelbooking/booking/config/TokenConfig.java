package ru.hotelbooking.booking.config;

import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.io.Resource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import ru.hotelbooking.common.security.JwtKeys;

@Configuration
public class TokenConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    JwtEncoder jwtEncoder(
            @Value("${app.jwt.public-key}") Resource publicKey,
            @Value("${app.jwt.private-key}") Resource privateKey) {
        var key =
                new RSAKey.Builder(JwtKeys.publicKey(publicKey))
                        .privateKey(JwtKeys.privateKey(privateKey))
                        .keyID("hotel-booking")
                        .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }
}
