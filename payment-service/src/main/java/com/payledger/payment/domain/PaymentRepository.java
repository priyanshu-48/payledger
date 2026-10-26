package com.payledger.payment.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByClientIdAndIdempotencyKey(String clientId, String idempotencyKey);

    List<Payment> findByStatusInAndUpdatedAtBefore(Collection<PaymentStatus> statuses, Instant cutoff);

    /** Admin list: newest first; either filter may be null. */
    @Query("select p from Payment p where (cast(:merchant as string) is null or p.merchantId = :merchant) "
            + "and (cast(:status as string) is null or cast(p.status as string) = cast(:status as string)) "
            + "order by p.createdAt desc, p.id")
    List<Payment> search(@Param("merchant") String merchant, @Param("status") String status, Pageable page);

    long countByStatusIn(Collection<PaymentStatus> statuses);

    /**
     * Conditional transition (ADR 0010). Returns 0 when the payment is no longer in {@code from}, i.e.
     * someone else moved it first. bankRef and reason are written as given: callers pass the current value
     * to keep it.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Payment p set p.status = :to, p.version = p.version + 1, p.updatedAt = :now, "
            + "p.bankRef = :bankRef, p.failureReason = :reason where p.id = :id and p.status = :from")
    int transition(@Param("id") UUID id, @Param("from") PaymentStatus from, @Param("to") PaymentStatus to,
                   @Param("bankRef") String bankRef, @Param("reason") String reason, @Param("now") Instant now);
}
