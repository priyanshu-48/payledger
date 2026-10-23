package com.payledger.common;

import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(UUID accountId) {
        super("insufficient funds in account " + accountId);
    }
}
