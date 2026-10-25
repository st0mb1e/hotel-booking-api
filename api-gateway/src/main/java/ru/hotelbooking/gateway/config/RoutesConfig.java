package ru.hotelbooking.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RoutesConfig {
    @Bean
    RouteLocator routes(
            RouteLocatorBuilder builder,
            @Value("${gateway.booking-uri:lb://booking-service}") String bookingUri,
            @Value("${gateway.hotel-uri:lb://hotel-service}") String hotelUri) {
        return builder.routes()
                .route(
                        "booking",
                        route ->
                                route.path(
                                                "/api/bookings",
                                                "/api/bookings/**",
                                                "/api/auth/**",
                                                "/api/users",
                                                "/api/users/**",
                                                "/booking",
                                                "/booking/**",
                                                "/bookings",
                                                "/user",
                                                "/user/**")
                                        .uri(bookingUri))
                .route(
                        "hotel",
                        route ->
                                route.path(
                                                "/api/hotels",
                                                "/api/hotels/**",
                                                "/api/rooms",
                                                "/api/rooms/**")
                                        .uri(hotelUri))
                .build();
    }
}
