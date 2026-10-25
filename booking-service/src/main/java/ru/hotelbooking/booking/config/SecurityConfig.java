package ru.hotelbooking.booking.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import ru.hotelbooking.common.security.JwtKeys;
import ru.hotelbooking.common.security.JwtSupport;
import ru.hotelbooking.common.web.RequestIdFilter;
import ru.hotelbooking.common.web.SecurityErrors;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    JwtDecoder jwtDecoder(
            @Value("${app.jwt.public-key}") Resource key,
            @Value("${app.jwt.issuer}") String issuer) {
        var decoder = NimbusJwtDecoder.withPublicKey(JwtKeys.publicKey(key)).build();
        decoder.setJwtValidator(JwtSupport.validator(issuer, Set.of(JwtSupport.USER_AUDIENCE)));
        return decoder;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, ObjectMapper mapper) throws Exception {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(JwtSupport::authorities);
        var entryPoint =
                (org.springframework.security.web.AuthenticationEntryPoint)
                        (request, response, error) ->
                                SecurityErrors.write(
                                        mapper,
                                        request,
                                        response,
                                        401,
                                        "UNAUTHORIZED",
                                        "Требуется действующий токен");
        var denied =
                (org.springframework.security.web.access.AccessDeniedHandler)
                        (request, response, error) ->
                                SecurityErrors.write(
                                        mapper,
                                        request,
                                        response,
                                        403,
                                        "ACCESS_DENIED",
                                        "Недостаточно прав");
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                "/api/auth/register",
                                                "/api/auth/login",
                                                "/user/register",
                                                "/user/auth",
                                                "/actuator/health",
                                                "/actuator/info",
                                                "/v3/api-docs/**",
                                                "/swagger-ui/**",
                                                "/swagger-ui.html")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(entryPoint)
                                        .accessDeniedHandler(denied))
                .oauth2ResourceServer(
                        oauth ->
                                oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                                        .authenticationEntryPoint(entryPoint)
                                        .accessDeniedHandler(denied))
                .addFilterBefore(new RequestIdFilter(), UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
