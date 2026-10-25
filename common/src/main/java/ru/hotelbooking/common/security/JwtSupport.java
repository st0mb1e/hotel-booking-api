package ru.hotelbooking.common.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;

public final class JwtSupport {
    public static final String USER_AUDIENCE = "hotel-booking-api";
    public static final String SERVICE_AUDIENCE = "hotel-service";

    private JwtSupport() {}

    public static OAuth2TokenValidator<Jwt> validator(String issuer, Set<String> audiences) {
        OAuth2TokenValidator<Jwt> audience =
                jwt ->
                        jwt.getAudience().stream().anyMatch(audiences::contains)
                                ? OAuth2TokenValidatorResult.success()
                                : OAuth2TokenValidatorResult.failure(
                                        new OAuth2Error(
                                                "invalid_token",
                                                "Недопустимая аудитория токена",
                                                null));
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), audience);
    }

    public static Collection<GrantedAuthority> authorities(Jwt jwt) {
        var authorities = new ArrayList<GrantedAuthority>();
        if (jwt.getAudience().contains(USER_AUDIENCE)) {
            var roles = jwt.getClaimAsStringList("roles");
            if (roles != null)
                roles.stream()
                        .filter(Set.of("USER", "ADMIN")::contains)
                        .forEach(
                                role ->
                                        authorities.add(
                                                new SimpleGrantedAuthority("ROLE_" + role)));
        }
        if (jwt.getAudience().contains(SERVICE_AUDIENCE)
                && "booking-service".equals(jwt.getSubject())) {
            var scope = jwt.getClaimAsString("scope");
            if (scope != null && Set.of(scope.split(" ")).contains("rooms:write")) {
                authorities.add(new SimpleGrantedAuthority("SCOPE_rooms:write"));
            }
        }
        return authorities;
    }
}
