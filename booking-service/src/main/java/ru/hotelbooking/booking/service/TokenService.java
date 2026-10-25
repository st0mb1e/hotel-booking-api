package ru.hotelbooking.booking.service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import ru.hotelbooking.booking.domain.User;
import ru.hotelbooking.common.security.JwtSupport;

@Service
public class TokenService {
    private final JwtEncoder encoder;
    private final Clock clock;
    private final String issuer;

    public TokenService(
            JwtEncoder encoder, Clock clock, @Value("${app.jwt.issuer}") String issuer) {
        this.encoder = encoder;
        this.clock = clock;
        this.issuer = issuer;
    }

    public String userToken(User user) {
        return encode(
                JwtClaimsSet.builder()
                        .subject(user.getId().toString())
                        .audience(List.of(JwtSupport.USER_AUDIENCE))
                        .claim("roles", List.of(user.getRole().name()))
                        .claim("username", user.getUsername()),
                Duration.ofHours(1));
    }

    public String serviceToken() {
        return encode(
                JwtClaimsSet.builder()
                        .subject("booking-service")
                        .audience(List.of(JwtSupport.SERVICE_AUDIENCE))
                        .claim("scope", "rooms:write"),
                Duration.ofMinutes(5));
    }

    private String encode(JwtClaimsSet.Builder builder, Duration lifetime) {
        var now = clock.instant();
        var claims =
                builder.issuer(issuer)
                        .issuedAt(now)
                        .notBefore(now)
                        .expiresAt(now.plus(lifetime))
                        .build();
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(SignatureAlgorithm.RS256)
                                        .keyId("hotel-booking")
                                        .build(),
                                claims))
                .getTokenValue();
    }
}
