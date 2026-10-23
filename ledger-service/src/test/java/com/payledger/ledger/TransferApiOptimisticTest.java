package com.payledger.ledger;

import org.springframework.test.context.TestPropertySource;

/** Every behaviour test of the pessimistic API, rerun against the optimistic strategy (ADR 0009). */
@TestPropertySource(properties = "ledger.locking-strategy=OPTIMISTIC")
class TransferApiOptimisticTest extends TransferApiTest {

    @Override
    boolean mayGiveUpUnderContention() {
        return true;
    }
}
