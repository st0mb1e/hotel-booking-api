package ru.hotelbooking.gateway.config;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import reactor.core.publisher.Mono;
import ru.hotelbooking.common.security.JwtKeys;
import ru.hotelbooking.common.security.JwtSupport;

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
    SecurityWebFilterChain security(ServerHttpSecurity http, GatewayErrorWriter writer) {
        var unauthorized =
                (org.springframework.security.web.server.ServerAuthenticationEntryPoint)
                        (exchange, error) ->
                                writer.write(
                                        exchange,
                                        HttpStatus.UNAUTHORIZED,
                                        "UNAUTHORIZED",
                                        "Требуется действующий токен");
        var forbidden =
                (org.springframework.security.web.server.authorization.ServerAccessDeniedHandler)
                        (exchange, error) ->
                                writer.write(
                                        exchange,
                                        HttpStatus.FORBIDDEN,
                                        "ACCESS_DENIED",
                                        "Недостаточно прав");
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(
                        auth ->
                                auth.pathMatchers(
                                                "/api/rooms/allocate",
                                                "/api/rooms/release",
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
                                                                this::authentication))
                                        .authenticationEntryPoint(unauthorized)
                                        .accessDeniedHandler(forbidden))
                .build();
    }

    private Mono<AbstractAuthenticationToken> authentication(Jwt token) {
        return Mono.just(new JwtAuthenticationToken(token, JwtSupport.authorities(token)));
    }
}
