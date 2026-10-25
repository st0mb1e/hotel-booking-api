package ru.hotelbooking.booking.config;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.hotelbooking.booking.client.HotelClientProperties;

@Configuration
@EnableConfigurationProperties(HotelClientProperties.class)
public class HotelClientConfig {
    @Bean(destroyMethod = "close")
    HttpClient hotelHttpClient(HotelClientProperties properties) {
        return HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
    }
}
