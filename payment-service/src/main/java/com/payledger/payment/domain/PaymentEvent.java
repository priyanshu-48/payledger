package com.payledger.payment.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "payment_events")
public class PaymentEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private UUID paymentId;
    @Enumerated(EnumType.STRING)
    private PaymentStatus fromStatus;
    @Enumerated(EnumType.STRING)
    private PaymentStatus toStatus;
    private Instant at;
    private String reason;

    protected PaymentEvent() {
    }

    public PaymentEvent(UUID paymentId, PaymentStatus fromStatus, PaymentStatus toStatus, String reason) {
        this.paymentId = paymentId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.at = Instant.now();
        this.reason = reason;
    }

    public Long getId() { return id; }
    public UUID getPaymentId() { return paymentId; }
    public PaymentStatus getFromStatus() { return fromStatus; }
    public PaymentStatus getToStatus() { return toStatus; }
    public Instant getAt() { return at; }
    public String getReason() { return reason; }
}
