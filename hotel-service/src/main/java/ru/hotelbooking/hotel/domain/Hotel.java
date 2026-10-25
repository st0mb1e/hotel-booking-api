package ru.hotelbooking.hotel.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "hotels")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Hotel {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 300)
    private String address;

    @Column(nullable = false)
    private boolean deleted;

    public Hotel(String name, String address) {
        update(name, address);
    }

    public void update(String name, String address) {
        this.name = name;
        this.address = address;
    }

    public void retire() {
        deleted = true;
    }
}
