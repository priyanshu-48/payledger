package com.payledger.payment.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Status, bank_ref, failure_reason, version and updated_at only change through
 * PaymentRepository.transition (a conditional UPDATE), never by editing this entity.
 */
@Entity
@Table(name = "payments")
public class Payment extends NewAwareEntity<UUID> {

    @Id
    private UUID id;
    private String merchantId;
    private UUID payerAccountId;
    private long amountMinor;
    private String currency;
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;
    private String clientId;
    private String idempotencyKey;
    private String requestHash;
    private String bankRef;
    private String failureReason;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    protected Payment() {
    }

    public Payment(UUID id, String merchantId, UUID payerAccountId, long amountMinor, String currency,
                   String clientId, String idempotencyKey, String requestHash) {
        this.id = id;
        this.merchantId = merchantId;
        this.payerAccountId = payerAccountId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = PaymentStatus.CREATED;
        this.clientId = clientId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @Override public UUID getId() { return id; }
    public String getMerchantId() { return merchantId; }
    public UUID getPayerAccountId() { return payerAccountId; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public String getClientId() { return clientId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public String getBankRef() { return bankRef; }
    public String getFailureReason() { return failureReason; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
