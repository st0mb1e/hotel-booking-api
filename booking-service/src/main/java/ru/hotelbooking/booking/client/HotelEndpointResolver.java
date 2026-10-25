package ru.hotelbooking.booking.client;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;

@Component
public class HotelEndpointResolver {
    private final DiscoveryClient discovery;
    private final String baseUrl;
    private final AtomicInteger nextInstance = new AtomicInteger();

    public HotelEndpointResolver(DiscoveryClient discovery, HotelClientProperties properties) {
        this.discovery = discovery;
        this.baseUrl = properties.baseUrl().replaceAll("/+$", "");
    }

    public URI resolve(String path) {
        if (!baseUrl.isBlank()) return URI.create(baseUrl).resolve(path);
        var instances = discovery.getInstances("hotel-service");
        if (instances.isEmpty()) {
            throw new HotelUnavailableException("Hotel Service не найден в Eureka");
        }
        return instances
                .get(Math.floorMod(nextInstance.getAndIncrement(), instances.size()))
                .getUri()
                .resolve(path);
    }
}
