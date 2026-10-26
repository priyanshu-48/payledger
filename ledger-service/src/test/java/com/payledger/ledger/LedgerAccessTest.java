package com.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payledger.common.Money;
import com.payledger.ledger.domain.Account;
import com.payledger.ledger.domain.AccountStatus;
import com.payledger.ledger.domain.AccountType;
import com.payledger.ledger.service.AccountService;
import com.payledger.ledger.service.LedgerService;
import com.payledger.ledger.service.TransferCommand;
import com.payledger.ledger.service.TransferFacade;
import com.payledger.security.Caller;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

/**
 * "Ownership checks happen in the service layer, not only by role" (SPEC 6.8): no HTTP and no filter chain here,
 * the services themselves refuse a Caller who does not own the account.
 */
class LedgerAccessTest extends ApiTestSupport {

    @Autowired AccountService accountService;
    @Autowired TransferFacade transfers;
    @Autowired LedgerService ledger;

    @Test
    void theServiceRefusesAUserWhoDoesNotOwnTheAccount() {
        Account alice = ledger.createAccount("alice", AccountType.USER_WALLET, "INR");
        Account bob = ledger.createAccount("bob", AccountType.USER_WALLET, "INR");
        Caller asAlice = Caller.of("alice", Caller.USER);

        assertThat(accountService.balance(asAlice, alice.getId()).minor()).isZero();
        assertThatThrownBy(() -> accountService.balance(asAlice, bob.getId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> transfers.transfer(asAlice,
                new TransferCommand("k-1", bob.getId(), alice.getId(), Money.of(100, "INR"))))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> accountService.setStatus(asAlice, alice.getId(), AccountStatus.FROZEN))
                .as("freezing is for ADMINs even on your own account").isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void internalCallersAndAdminsAreNotBlocked() {
        Account settlement = ledger.createAccount("platform", AccountType.SETTLEMENT, "INR");
        Account bob = ledger.createAccount("bob", AccountType.USER_WALLET, "INR");

        transfers.transfer(Caller.system(), new TransferCommand("k-sys", settlement.getId(), bob.getId(), Money.of(500, "INR")));
        transfers.transfer(Caller.of("root", Caller.ADMIN), new TransferCommand("k-adm", settlement.getId(), bob.getId(), Money.of(500, "INR")));

        assertThat(accountService.balance(Caller.of("bob", Caller.USER), bob.getId()).minor()).isEqualTo(1_000);
    }
}
