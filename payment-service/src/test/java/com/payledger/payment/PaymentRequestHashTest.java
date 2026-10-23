package com.payledger.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.payledger.payment.service.PaymentRequestHash;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentRequestHashTest {

    private static final UUID PAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void isStableAndHex() {
        String hash = PaymentRequestHash.of("m1", PAYER, 50_000, "INR");
        assertThat(hash).isEqualTo(PaymentRequestHash.of("m1", PAYER, 50_000, "INR")).matches("[0-9a-f]{64}");
    }

    @Test
    void changesWhenAnyFieldChanges() {
        String base = PaymentRequestHash.of("m1", PAYER, 50_000, "INR");
        assertThat(PaymentRequestHash.of("m2", PAYER, 50_000, "INR")).isNotEqualTo(base);
        assertThat(PaymentRequestHash.of("m1", UUID.randomUUID(), 50_000, "INR")).isNotEqualTo(base);
        assertThat(PaymentRequestHash.of("m1", PAYER, 50_001, "INR")).isNotEqualTo(base);
        assertThat(PaymentRequestHash.of("m1", PAYER, 50_000, "USD")).isNotEqualTo(base);
    }
}
