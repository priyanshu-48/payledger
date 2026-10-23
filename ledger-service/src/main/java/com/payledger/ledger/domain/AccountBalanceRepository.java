package com.payledger.ledger.domain;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountBalanceRepository extends JpaRepository<AccountBalance, UUID> {

    /** SELECT ... FOR UPDATE on one balance row. Callers take locks in sorted account-id order (ADR 0007). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from AccountBalance b where b.accountId = :id")
    Optional<AccountBalance> findForUpdate(@Param("id") UUID id);
}
