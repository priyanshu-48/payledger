package com.payledger.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Amount in minor units (paise) plus ISO-4217 currency. Never a double. */
public record Money(long minor, String currency) {

    public Money {
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be 3 upper-case letters: " + currency);
        }
    }

    public static Money of(long minor, String currency) {
        return new Money(minor, currency);
    }

    public Money plus(Money other) {
        requireSame(other);
        return new Money(Math.addExact(minor, other.minor), currency);
    }

    public Money minus(Money other) {
        requireSame(other);
        return new Money(Math.subtractExact(minor, other.minor), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minor), currency);
    }

    public boolean isPositive() {
        return minor > 0;
    }

    /** API boundary only: major units with scale 2, e.g. 500.00. */
    public BigDecimal toMajor() {
        return BigDecimal.valueOf(minor, 2);
    }

    /** API boundary only: rejects more than 2 decimals instead of rounding money silently. */
    public static Money fromMajor(BigDecimal major, String currency) {
        return new Money(major.setScale(2, RoundingMode.UNNECESSARY).unscaledValue().longValueExact(), currency);
    }

    private void requireSame(Money other) {
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }
}
