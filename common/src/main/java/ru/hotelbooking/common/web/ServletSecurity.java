package ru.hotelbooking.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.core.io.Resource;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import ru.hotelbooking.common.security.JwtKeys;
import ru.hotelbooking.common.security.JwtSupport;

public final class ServletSecurity {
    private ServletSecurity() {}

    public static JwtDecoder decoder(Resource key, String issuer, Set<String> audiences) {
        var decoder = NimbusJwtDecoder.withPublicKey(JwtKeys.publicKey(key)).build();
        decoder.setJwtValidator(JwtSupport.validator(issuer, audiences));
        return decoder;
    }

    public static SecurityFilterChain configure(
            HttpSecurity http, ObjectMapper mapper, String... publicEndpoints) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(JwtSupport::authorities);
        var unauthorized = unauthorized(mapper);
        var forbidden = forbidden(mapper);
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(publicPaths(publicEndpoints))
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(unauthorized)
                                        .accessDeniedHandler(forbidden))
                .oauth2ResourceServer(
                        oauth ->
                                oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                                        .authenticationEntryPoint(unauthorized)
                                        .accessDeniedHandler(forbidden))
                .addFilterBefore(new RequestIdFilter(), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private static String[] publicPaths(String[] extra) {
        return Stream.concat(
                        Stream.of(
                                "/actuator/health",
                                "/actuator/info",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"),
                        Arrays.stream(extra))
                .toArray(String[]::new);
    }

    private static AuthenticationEntryPoint unauthorized(ObjectMapper mapper) {
        return (request, response, error) ->
                SecurityErrors.write(
                        mapper,
                        request,
                        response,
                        401,
                        "UNAUTHORIZED",
                        "Требуется действующий токен");
    }

    private static AccessDeniedHandler forbidden(ObjectMapper mapper) {
        return (request, response, error) ->
                SecurityErrors.write(
                        mapper, request, response, 403, "ACCESS_DENIED", "Недостаточно прав");
    }
}
