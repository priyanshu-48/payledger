package com.payledger.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void addsAndSubtractsSameCurrency() {
        assertThat(Money.of(500, "INR").plus(Money.of(250, "INR"))).isEqualTo(Money.of(750, "INR"));
        assertThat(Money.of(500, "INR").minus(Money.of(250, "INR"))).isEqualTo(Money.of(250, "INR"));
        assertThat(Money.of(5, "INR").negate()).isEqualTo(Money.of(-5, "INR"));
    }

    @Test
    void rejectsCurrencyMismatch() {
        assertThatThrownBy(() -> Money.of(1, "INR").plus(Money.of(1, "USD")))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void overflowFailsLoudly() {
        assertThatThrownBy(() -> Money.of(Long.MAX_VALUE, "INR").plus(Money.of(1, "INR")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void rejectsBadCurrency() {
        assertThatThrownBy(() -> Money.of(1, "rupee")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void majorUnitConversionIsExactOrFails() {
        assertThat(Money.fromMajor(new BigDecimal("500.25"), "INR")).isEqualTo(Money.of(50025, "INR"));
        assertThat(Money.of(50025, "INR").toMajor()).isEqualByComparingTo("500.25");
        assertThatThrownBy(() -> Money.fromMajor(new BigDecimal("0.001"), "INR"))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void decimalPlacesFollowTheCurrency() {
        // JPY has no minor unit: 500 yen is stored as 500, and a fraction is refused
        assertThat(Money.fromMajor(new BigDecimal("500"), "JPY")).isEqualTo(Money.of(500, "JPY"));
        assertThat(Money.of(500, "JPY").toMajor()).isEqualByComparingTo("500");
        assertThatThrownBy(() -> Money.fromMajor(new BigDecimal("500.5"), "JPY"))
                .isInstanceOf(ArithmeticException.class);
        // KWD has three
        assertThat(Money.fromMajor(new BigDecimal("1.234"), "KWD")).isEqualTo(Money.of(1234, "KWD"));
        assertThat(Money.of(1234, "KWD").toMajor()).isEqualByComparingTo("1.234");
        // two, as before
        assertThat(Money.fromMajor(new BigDecimal("100.50"), "USD")).isEqualTo(Money.of(10050, "USD"));
    }

    @Test
    void unknownCurrencyCannotBeConverted() {
        assertThatThrownBy(() -> Money.fromMajor(new BigDecimal("1"), "ZZZ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.of(1, "ZZZ").toMajor()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.fromMajor(new BigDecimal("1"), "XXX")).isInstanceOf(IllegalArgumentException.class);
    }
}
