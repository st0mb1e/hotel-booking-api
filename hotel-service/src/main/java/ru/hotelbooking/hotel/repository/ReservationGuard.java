package ru.hotelbooking.hotel.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ReservationGuard {
    private final JdbcTemplate jdbc;

    public ReservationGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock() {
        jdbc.queryForObject("select id from reservation_guard where id = 1 for update", Long.class);
    }
}
