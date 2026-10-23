package com.payledger.common;

public class CurrencyMismatchException extends RuntimeException {
    public CurrencyMismatchException(String left, String right) {
        super("currency mismatch: " + left + " vs " + right);
    }
}
