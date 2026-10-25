package ru.hotelbooking.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.hotelbooking.common.api.ApiError;
import ru.hotelbooking.common.security.*;
import ru.hotelbooking.common.web.RequestIds;

@Configuration
public class SecurityConfig {
    @Bean
    ReactiveJwtDecoder jwtDecoder(
            @Value("${app.jwt.public-key}") Resource key,
            @Value("${app.jwt.issuer}") String issuer) {
        var decoder = NimbusReactiveJwtDecoder.withPublicKey(JwtKeys.publicKey(key)).build();
        decoder.setJwtValidator(JwtSupport.validator(issuer, Set.of(JwtSupport.USER_AUDIENCE)));
        return decoder;
    }

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http, ObjectMapper mapper) {
        var unauthorized =
                (org.springframework.security.web.server.ServerAuthenticationEntryPoint)
                        (exchange, error) ->
                                write(
                                        mapper,
                                        exchange,
                                        HttpStatus.UNAUTHORIZED,
                                        "UNAUTHORIZED",
                                        "Требуется действующий токен");
        var forbidden =
                (org.springframework.security.web.server.authorization.ServerAccessDeniedHandler)
                        (exchange, error) ->
                                write(
                                        mapper,
                                        exchange,
                                        HttpStatus.FORBIDDEN,
                                        "ACCESS_DENIED",
                                        "Недостаточно прав");
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .securityContextRepository(
                        org.springframework.security.web.server.context
                                .NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(
                        auth ->
                                auth.pathMatchers(
                                                "/api/rooms/*/confirm-availability",
                                                "/api/rooms/*/release")
                                        .denyAll()
                                        .pathMatchers(
                                                "/api/auth/register",
                                                "/api/auth/login",
                                                "/user/register",
                                                "/user/auth",
                                                "/actuator/health",
                                                "/actuator/info")
                                        .permitAll()
                                        .anyExchange()
                                        .hasAnyRole("USER", "ADMIN"))
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(unauthorized)
                                        .accessDeniedHandler(forbidden))
                .oauth2ResourceServer(
                        oauth ->
                                oauth.jwt(
                                                jwt ->
                                                        jwt.jwtAuthenticationConverter(
                                                                token ->
                                                                        Mono
                                                                                .<AbstractAuthenticationToken>
                                                                                        just(
                                                                                                new JwtAuthenticationToken(
                                                                                                        token,
                                                                                                        JwtSupport
                                                                                                                .authorities(
                                                                                                                        token)))))
                                        .authenticationEntryPoint(unauthorized)
                                        .accessDeniedHandler(forbidden))
                .build();
    }

    private Mono<Void> write(
            ObjectMapper mapper,
            ServerWebExchange exchange,
            HttpStatus status,
            String code,
            String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (status == HttpStatus.UNAUTHORIZED)
            exchange.getResponse().getHeaders().set("WWW-Authenticate", "Bearer");
        try {
            var error =
                    new ApiError(
                            Instant.now(),
                            status.value(),
                            code,
                            message,
                            exchange.getRequest().getPath().value(),
                            exchange.getResponse().getHeaders().getFirst(RequestIds.HEADER),
                            Map.of());
            return exchange.getResponse()
                    .writeWith(
                            Mono.just(
                                    exchange.getResponse()
                                            .bufferFactory()
                                            .wrap(mapper.writeValueAsBytes(error))));
        } catch (Exception e) {
            return Mono.error(e);
        }
    }
}
