package com.payledger.payment.web;

import com.payledger.common.Money;
import com.payledger.payment.domain.Payment;
import com.payledger.payment.domain.PaymentEvent;
import com.payledger.payment.domain.PaymentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** API shapes; BigDecimal appears only here, at the boundary. */
public final class PaymentDtos {

    private PaymentDtos() {
    }

    public record CreatePaymentRequest(@NotBlank String merchantId, @NotNull UUID payerAccountId,
                                       @NotNull @Positive BigDecimal amount,
                                       @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {
    }

    public record PaymentResponse(UUID id, String merchantId, UUID payerAccountId, BigDecimal amount, String currency,
                                  PaymentStatus status, String bankRef, String failureReason, Instant createdAt,
                                  Instant updatedAt) {
        static PaymentResponse of(Payment p) {
            return new PaymentResponse(p.getId(), p.getMerchantId(), p.getPayerAccountId(),
                    Money.of(p.getAmountMinor(), p.getCurrency()).toMajor(), p.getCurrency(), p.getStatus(),
                    p.getBankRef(), p.getFailureReason(), p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    public record EventResponse(PaymentStatus from, PaymentStatus to, Instant at, String reason) {
        static EventResponse of(PaymentEvent e) {
            return new EventResponse(e.getFromStatus(), e.getToStatus(), e.getAt(), e.getReason());
        }
    }
}
