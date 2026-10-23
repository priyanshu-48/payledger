package com.payledger.ledger.domain;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /** The merchant's ledger account for a currency (unique per ADR 0014 / migration V3). */
    Optional<Account> findByOwnerIdAndTypeAndCurrency(String ownerId, AccountType type, String currency);
}
