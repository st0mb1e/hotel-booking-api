package ru.hotelbooking.hotel.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "rooms", uniqueConstraints = @UniqueConstraint(columnNames = {"hotel_id", "number"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Room {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hotel_id")
    private Hotel hotel;

    @Column(nullable = false, length = 30)
    private String number;

    @Column(nullable = false)
    private boolean available = true;

    @Column(name = "times_booked", nullable = false)
    private long timesBooked;

    @Column(nullable = false)
    private boolean deleted;

    @Version private long version;

    public Room(Hotel hotel, String number, boolean available) {
        this.hotel = hotel;
        this.number = number;
        this.available = available;
    }

    public void update(String number, boolean available) {
        this.number = number;
        this.available = available;
    }

    public void countReservation() {
        timesBooked++;
    }

    public void undoReservationCount() {
        if (timesBooked > 0) timesBooked--;
    }

    public void retire() {
        deleted = true;
        available = false;
    }
}
