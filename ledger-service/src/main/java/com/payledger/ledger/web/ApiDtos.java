package com.payledger.ledger.web;

import com.payledger.ledger.domain.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** API shapes. BigDecimal lives only here, at the boundary; the domain uses long minor units. */
public final class ApiDtos {

    private ApiDtos() {
    }

    public record CreateAccountRequest(@NotBlank String ownerId, @NotNull AccountType type,
                                       @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {
    }

    public record AccountResponse(UUID id, String ownerId, AccountType type, String currency, String status) {
    }

    public record BalanceResponse(UUID accountId, BigDecimal balance, String currency) {
    }

    public record TransferRequest(@NotNull UUID fromAccountId, @NotNull UUID toAccountId,
                                  @NotNull @Positive BigDecimal amount,
                                  @NotNull @Pattern(regexp = "[A-Z]{3}") String currency) {
    }

    public record TransferResponse(UUID entryId, String idempotencyKey, UUID fromAccountId, UUID toAccountId,
                                   BigDecimal amount, String currency, Instant createdAt) {
    }
}
