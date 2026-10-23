package com.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payledger.common.Money;
import com.payledger.ledger.domain.AccountBalance;
import com.payledger.ledger.service.LedgerExceptions.TransferContentionException;
import com.payledger.ledger.service.OptimisticLedgerService;
import com.payledger.ledger.service.OptimisticTransferExecutor;
import com.payledger.ledger.service.TransferCommand;
import com.payledger.ledger.service.TransferResult;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** The retry loop on its own: no database, conflicts are injected. */
class OptimisticRetryTest {

    private final TransferCommand cmd = new TransferCommand("k", UUID.randomUUID(), UUID.randomUUID(), Money.of(100, "INR"));
    private final TransferResult ok = new TransferResult(UUID.randomUUID(), "k", cmd.from(), cmd.to(), cmd.amount(), Instant.now(), false);

    private ObjectOptimisticLockingFailureException versionConflict() {
        return new ObjectOptimisticLockingFailureException(AccountBalance.class, UUID.randomUUID());
    }

    @Test
    void conflictsAreRetriedUntilTheTransferSucceeds() {
        OptimisticLedgerService service = mock(OptimisticLedgerService.class);
        when(service.attempt(any()))
                .thenThrow(versionConflict())
                .thenThrow(new DeadlockLoserDataAccessException("deadlock detected", null))
                .thenReturn(ok);
        OptimisticTransferExecutor executor = new OptimisticTransferExecutor(service, 5);

        assertThat(executor.transfer(cmd)).isEqualTo(ok);

        verify(service, times(3)).attempt(cmd);
        assertThat(executor.retryCount()).isEqualTo(2);
    }

    @Test
    void givesUpAfterMaxAttemptsWithoutWritingAnything() {
        OptimisticLedgerService service = mock(OptimisticLedgerService.class);
        when(service.attempt(any())).thenThrow(versionConflict());
        OptimisticTransferExecutor executor = new OptimisticTransferExecutor(service, 3);

        assertThatThrownBy(() -> executor.transfer(cmd)).isInstanceOf(TransferContentionException.class);

        verify(service, times(3)).attempt(cmd);
    }

    @Test
    void businessErrorsAreNeverRetried() {
        OptimisticLedgerService service = mock(OptimisticLedgerService.class);
        when(service.attempt(any())).thenThrow(new IllegalStateException("insufficient funds"));
        OptimisticTransferExecutor executor = new OptimisticTransferExecutor(service, 5);

        assertThatThrownBy(() -> executor.transfer(cmd)).isInstanceOf(IllegalStateException.class);

        verify(service, times(1)).attempt(cmd);
    }
}
