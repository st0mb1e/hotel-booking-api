package ru.hotelbooking.booking.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import ru.hotelbooking.common.contract.ReleaseCommand;

@Entity
@Table(
        name = "bookings",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "request_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Booking {
    public enum Status {
        PENDING,
        CONFIRMED,
        CANCELLED
    }

    @Id private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    private Long roomId;
    private Long hotelId;

    @Column(nullable = false)
    private boolean autoSelect;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(nullable = false, length = 128)
    private String requestKey;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(length = 40)
    private String failureCode;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(nullable = false)
    private boolean compensationPending;

    @Column(nullable = false)
    private int compensationAttempts;

    private Instant nextCompensationAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private ReleaseCommand.Reason cancellationReason;

    @Version private long version;

    public Booking(
            User user,
            Long roomId,
            Long hotelId,
            boolean autoSelect,
            LocalDate start,
            LocalDate end,
            String requestKey,
            String fingerprint,
            Instant now) {
        id = UUID.randomUUID();
        this.user = user;
        this.roomId = roomId;
        this.hotelId = hotelId;
        this.autoSelect = autoSelect;
        startDate = start;
        endDate = end;
        this.requestKey = requestKey;
        this.fingerprint = fingerprint;
        status = Status.PENDING;
        createdAt = now;
        updatedAt = now;
    }

    public void assign(long roomId, Instant now) {
        this.roomId = roomId;
        updatedAt = now;
    }

    public void confirm(Instant now) {
        status = Status.CONFIRMED;
        updatedAt = now;
    }

    public void cancel(String failureCode, ReleaseCommand.Reason reason, Instant now) {
        cancel(failureCode, reason, now, true);
    }

    public void cancel(
            String failureCode,
            ReleaseCommand.Reason reason,
            Instant now,
            boolean needsCompensation) {
        status = Status.CANCELLED;
        this.failureCode = failureCode;
        cancellationReason = reason;
        compensationPending = needsCompensation && roomId != null;
        nextCompensationAt = compensationPending ? now : null;
        updatedAt = now;
    }

    public void compensationDone(Instant now) {
        compensationPending = false;
        nextCompensationAt = null;
        updatedAt = now;
    }

    public void compensationFailed(Instant now) {
        compensationAttempts++;
        nextCompensationAt = now.plusSeconds(Math.min(60, 1L << Math.min(compensationAttempts, 6)));
        updatedAt = now;
    }
}
