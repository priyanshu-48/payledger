package com.payledger.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/** Amount in minor units (paise) plus ISO-4217 currency. Never a double. */
public record Money(long minor, String currency) {

    public Money {
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be 3 upper-case letters");
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

    /** Decimal places of the currency's major unit (ISO 4217): 2 for INR and USD, 0 for JPY, 3 for KWD. */
    public static int fractionDigits(String currency) {
        int digits;
        try {
            digits = Currency.getInstance(currency).getDefaultFractionDigits();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown currency " + currency, e);
        }
        if (digits < 0) {
            throw new IllegalArgumentException(currency + " has no minor unit");
        }
        return digits;
    }

    /** API boundary only: major units at the currency's scale, e.g. 500.00 for INR, 500 for JPY. */
    public BigDecimal toMajor() {
        return BigDecimal.valueOf(minor, fractionDigits(currency));
    }

    /** API boundary only: rejects more decimals than the currency has instead of rounding money silently. */
    public static Money fromMajor(BigDecimal major, String currency) {
        int digits = fractionDigits(currency);
        return new Money(major.setScale(digits, RoundingMode.UNNECESSARY).unscaledValue().longValueExact(), currency);
    }

    private void requireSame(Money other) {
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }
}
