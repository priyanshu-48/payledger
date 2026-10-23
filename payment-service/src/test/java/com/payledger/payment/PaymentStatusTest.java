package com.payledger.payment;

import static com.payledger.payment.domain.PaymentStatus.AUTHORIZATION_UNKNOWN;
import static com.payledger.payment.domain.PaymentStatus.AUTHORIZED;
import static com.payledger.payment.domain.PaymentStatus.CAPTURED;
import static com.payledger.payment.domain.PaymentStatus.CREATED;
import static com.payledger.payment.domain.PaymentStatus.FAILED;
import static com.payledger.payment.domain.PaymentStatus.REFUNDED;
import static com.payledger.payment.domain.PaymentStatus.SETTLED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payledger.payment.domain.IllegalTransitionException;
import com.payledger.payment.domain.PaymentStatus;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The transition table is restated here on purpose: changing the machine must change this test. */
class PaymentStatusTest {

    private static final Map<PaymentStatus, Set<PaymentStatus>> EXPECTED = Map.of(
            CREATED, EnumSet.of(AUTHORIZED, FAILED, AUTHORIZATION_UNKNOWN),
            AUTHORIZATION_UNKNOWN, EnumSet.of(AUTHORIZED, FAILED),
            AUTHORIZED, EnumSet.of(CAPTURED, FAILED),
            CAPTURED, EnumSet.of(SETTLED, REFUNDED),
            SETTLED, EnumSet.of(REFUNDED),
            FAILED, EnumSet.noneOf(PaymentStatus.class),
            REFUNDED, EnumSet.noneOf(PaymentStatus.class));

    @Test
    void everyPairIsAllowedExactlyWhenTheTableSaysSo() {
        for (PaymentStatus from : PaymentStatus.values()) {
            for (PaymentStatus to : PaymentStatus.values()) {
                boolean allowed = EXPECTED.get(from).contains(to);
                assertThat(from.canMoveTo(to)).as("%s -> %s", from, to).isEqualTo(allowed);
                if (allowed) {
                    assertThatCode(() -> from.requireMoveTo(to)).doesNotThrowAnyException();
                } else {
                    assertThatThrownBy(() -> from.requireMoveTo(to)).isInstanceOf(IllegalTransitionException.class);
                }
            }
        }
    }

    @Test
    void terminalAndUnresolvedStates() {
        assertThat(PaymentStatus.values()).allSatisfy(s -> assertThat(s.isTerminal()).isEqualTo(EXPECTED.get(s).isEmpty()));
        assertThat(CREATED.isUnresolved()).isTrue();
        assertThat(AUTHORIZATION_UNKNOWN.isUnresolved()).isTrue();
        assertThat(AUTHORIZED.isUnresolved()).isFalse();
    }
}
