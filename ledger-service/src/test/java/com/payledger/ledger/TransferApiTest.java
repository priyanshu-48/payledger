package com.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class TransferApiTest extends ApiTestSupport {

    @Test
    void fundingAndPeerTransferMoveExactlyTheRightMoney() {
        UUID settlement = createAccount("SETTLEMENT", "INR");
        UUID alice = fundedWallet(settlement, "500.00");
        UUID bob = createAccount("USER_WALLET", "INR");

        ResponseEntity<Map> r = transfer("t-1", alice, bob, "200.25", "INR");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(balance(alice)).isEqualByComparingTo("299.75");
        assertThat(balance(bob)).isEqualByComparingTo("200.25");
        assertThat(balance(settlement)).isEqualByComparingTo("-500.00");
        assertThat(count("journal_entries")).isEqualTo(2);
        assertThat(count("postings")).isEqualTo(4);
    }

    @Test
    void insufficientFundsIs422AndWritesNothing() {
        UUID settlement = createAccount("SETTLEMENT", "INR");
        UUID alice = fundedWallet(settlement, "100.00");
        UUID bob = createAccount("USER_WALLET", "INR");
        long entriesBefore = count("journal_entries");

        ResponseEntity<Map> r = transfer("t-2", alice, bob, "100.01", "INR");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(balance(alice)).isEqualByComparingTo("100.00");
        assertThat(balance(bob)).isEqualByComparingTo("0.00");
        assertThat(count("journal_entries")).isEqualTo(entriesBefore);
    }

    @Test
    void replayWithSameKeyAndBodyReturnsOriginalWithoutMovingMoneyAgain() {
        UUID settlement = createAccount("SETTLEMENT", "INR");
        UUID alice = fundedWallet(settlement, "500.00");
        UUID bob = createAccount("USER_WALLET", "INR");

        ResponseEntity<Map> first = transfer("t-3", alice, bob, "50.00", "INR");
        ResponseEntity<Map> second = transfer("t-3", alice, bob, "50.00", "INR");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("entryId")).isEqualTo(first.getBody().get("entryId"));
        assertThat(balance(bob)).isEqualByComparingTo("50.00");
        assertThat(count("journal_entries")).isEqualTo(2);
    }

    @Test
    void sameKeyWithDifferentBodyIs422() {
        UUID settlement = createAccount("SETTLEMENT", "INR");
        UUID alice = fundedWallet(settlement, "500.00");
        UUID bob = createAccount("USER_WALLET", "INR");
        transfer("t-4", alice, bob, "50.00", "INR");

        ResponseEntity<Map> r = transfer("t-4", alice, bob, "60.00", "INR");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(balance(bob)).isEqualByComparingTo("50.00");
    }

    @Test
    void currencyMismatchIs422() {
        UUID settlement = createAccount("SETTLEMENT", "INR");
        UUID alice = fundedWallet(settlement, "500.00");
        UUID bob = createAccount("USER_WALLET", "INR");

        assertThat(transfer("t-5", alice, bob, "1.00", "USD").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        UUID dollars = createAccount("USER_WALLET", "USD");
        assertThat(transfer("t-5b", alice, dollars, "1.00", "INR").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void badRequestsAre400() {
        UUID settlement = createAccount("SETTLEMENT", "INR");
        UUID alice = fundedWallet(settlement, "500.00");
        UUID bob = createAccount("USER_WALLET", "INR");

        assertThat(transfer("t-6", alice, alice, "1.00", "INR").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(transfer("t-7", alice, bob, "0.00", "INR").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(transfer("t-8", alice, bob, "-5.00", "INR").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(transfer("t-9", alice, bob, "1.005", "INR").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(transfer(null, alice, bob, "1.00", "INR").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(balance(alice)).isEqualByComparingTo("500.00");
    }

    @Test
    void unknownAndFrozenAccounts() {
        UUID settlement = createAccount("SETTLEMENT", "INR");
        UUID alice = fundedWallet(settlement, "500.00");
        UUID bob = createAccount("USER_WALLET", "INR");

        assertThat(transfer("t-10", alice, UUID.randomUUID(), "1.00", "INR").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        jdbc.update("UPDATE accounts SET status = 'FROZEN' WHERE id = ?", bob);
        assertThat(transfer("t-11", alice, bob, "1.00", "INR").getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(balance(alice)).isEqualByComparingTo("500.00");
    }

    @Test
    void tenParallelRequestsWithTheSameKeyCreateExactlyOneEntry() throws Exception {
        UUID settlement = createAccount("SETTLEMENT", "INR");
        UUID alice = fundedWallet(settlement, "500.00");
        UUID bob = createAccount("USER_WALLET", "INR");

        List<ResponseEntity<Map>> results = runInParallel(10,
                () -> transfer("t-par", alice, bob, "10.00", "INR"));

        Set<Object> entryIds = results.stream().map(r -> r.getBody().get("entryId")).collect(Collectors.toSet());
        assertThat(results).allSatisfy(r -> assertThat(r.getStatusCode().is2xxSuccessful()).isTrue());
        assertThat(entryIds).hasSize(1);
        assertThat(balance(bob)).isEqualByComparingTo("10.00");
        assertThat(count("journal_entries")).isEqualTo(2);
    }

    @Test
    void sameKeyRacingOnDifferentAccountsLetsExactlyOneWin() throws Exception {
        UUID settlement = createAccount("SETTLEMENT", "INR");
        UUID alice = fundedWallet(settlement, "500.00");
        UUID bob = createAccount("USER_WALLET", "INR");
        UUID carol = createAccount("USER_WALLET", "INR");

        List<Callable<ResponseEntity<Map>>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID to = i % 2 == 0 ? bob : carol;
            calls.add(() -> transfer("t-race", alice, to, "10.00", "INR"));
        }
        List<ResponseEntity<Map>> results = runAll(calls);

        long moved = results.stream().filter(r -> r.getStatusCode().is2xxSuccessful()).count();
        assertThat(moved).isGreaterThanOrEqualTo(1);
        assertThat(count("journal_entries")).isEqualTo(2);
        assertThat(balance(bob).add(balance(carol))).isEqualByComparingTo("10.00");
        assertThat(balance(alice)).isEqualByComparingTo("490.00");
    }

    @Test
    void oppositeTransfersRunningTogetherDoNotDeadlockOrLoseMoney() throws Exception {
        UUID settlement = createAccount("SETTLEMENT", "INR");
        UUID alice = fundedWallet(settlement, "1000.00");
        UUID bob = fundedWallet(settlement, "1000.00");

        List<Callable<ResponseEntity<Map>>> calls = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            int n = i;
            calls.add(() -> transfer("ab-" + n, alice, bob, "5.00", "INR"));
            calls.add(() -> transfer("ba-" + n, bob, alice, "5.00", "INR"));
        }
        List<ResponseEntity<Map>> results = runAll(calls);

        // calls alternate alice->bob, bob->alice; a transfer either happened (201) or, only for the
        // optimistic strategy under heavy contention, was abandoned with nothing written (409)
        int aliceToBob = 0;
        int bobToAlice = 0;
        for (int i = 0; i < results.size(); i++) {
            HttpStatus status = (HttpStatus) results.get(i).getStatusCode();
            if (status == HttpStatus.CREATED) {
                if (i % 2 == 0) {
                    aliceToBob++;
                } else {
                    bobToAlice++;
                }
            } else {
                assertThat(mayGiveUpUnderContention()).as("unexpected status %s", status).isTrue();
                assertThat(status).isEqualTo(HttpStatus.CONFLICT);
            }
        }
        BigDecimal net = new BigDecimal(5 * (bobToAlice - aliceToBob));
        assertThat(balance(alice).add(balance(bob))).isEqualByComparingTo(new BigDecimal("2000.00"));
        assertThat(balance(alice)).isEqualByComparingTo(new BigDecimal("1000.00").add(net));
        assertThat(balance(bob)).isEqualByComparingTo(new BigDecimal("1000.00").subtract(net));
    }

    /** The pessimistic strategy queues on row locks and never abandons a transfer. */
    boolean mayGiveUpUnderContention() {
        return false;
    }

    private List<ResponseEntity<Map>> runInParallel(int n, Callable<ResponseEntity<Map>> call) throws Exception {
        List<Callable<ResponseEntity<Map>>> calls = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            calls.add(call);
        }
        return runAll(calls);
    }

    private List<ResponseEntity<Map>> runAll(List<Callable<ResponseEntity<Map>>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<ResponseEntity<Map>> out = new ArrayList<>();
            for (Future<ResponseEntity<Map>> f : pool.invokeAll(calls)) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }
}
