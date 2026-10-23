package com.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.payledger.common.InsufficientFundsException;
import com.payledger.common.Money;
import com.payledger.ledger.domain.Account;
import com.payledger.ledger.domain.AccountType;
import com.payledger.ledger.service.LedgerExceptions.InvalidTransferException;
import com.payledger.ledger.service.LedgerService;
import com.payledger.ledger.service.TransferCommand;
import com.payledger.ledger.service.TransferFacade;
import com.payledger.ledger.service.TransferResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SPEC section 7 property test: random sequences of funding, transfers and retries against a real
 * Postgres. Whatever happens, money is conserved, no wallet goes negative, every entry balances,
 * and balances equal what the postings say. Tries: jqwik.properties (1000) or -Djqwik.tries.default=10000.
 */
class LedgerInvariantPropertyTest {

    private static final int ACCOUNTS = 6; // index 0 = SETTLEMENT (may go negative), 1..5 = USER_WALLET
    private static final String INR = "INR";

    private static ConfigurableApplicationContext context;
    private static LedgerService ledger;
    private static TransferFacade transfers;
    private static JdbcTemplate jdbc;

    record Op(int from, int to, long amountMinor, boolean retry) {
    }

    @BeforeContainer
    static void start() {
        context = new SpringApplicationBuilder(LedgerApplication.class)
                .web(WebApplicationType.NONE)
                // command-line args outrank application.yml, whose ${DB_URL} placeholders are unset here
                .run("--spring.datasource.url=" + AbstractPostgresTest.POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + AbstractPostgresTest.POSTGRES.getUsername(),
                        "--spring.datasource.password=" + AbstractPostgresTest.POSTGRES.getPassword());
        ledger = context.getBean(LedgerService.class);
        transfers = context.getBean(TransferFacade.class);
        jdbc = context.getBean(JdbcTemplate.class);
    }

    @AfterContainer
    static void stop() {
        if (context != null) {
            context.close();
        }
    }

    @Provide
    Arbitrary<List<Op>> operations() {
        Arbitrary<Integer> from = Arbitraries.of(0, 0, 0, 1, 2, 3, 4, 5); // fund often, so wallets can spend
        Arbitrary<Integer> to = Arbitraries.integers().between(0, ACCOUNTS - 1);
        Arbitrary<Long> amount = Arbitraries.longs().between(1, 50_000);
        Arbitrary<Boolean> retry = Arbitraries.of(false, false, false, true);
        return Combinators.combine(from, to, amount, retry).as(Op::new).list().ofMinSize(1).ofMaxSize(12);
    }

    @Property
    void moneyIsConservedAndEveryEntryBalances(@ForAll("operations") List<Op> ops) {
        List<Account> accounts = new ArrayList<>();
        accounts.add(ledger.createAccount("settlement", AccountType.SETTLEMENT, INR));
        for (int i = 1; i < ACCOUNTS; i++) {
            accounts.add(ledger.createAccount("wallet-" + i, AccountType.USER_WALLET, INR));
        }
        long[] model = new long[ACCOUNTS];
        String run = UUID.randomUUID().toString();

        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            boolean shouldSucceed = op.from() != op.to() && (op.from() == 0 || model[op.from()] >= op.amountMinor());
            TransferCommand cmd = new TransferCommand(run + "-" + i, accounts.get(op.from()).getId(),
                    accounts.get(op.to()).getId(), Money.of(op.amountMinor(), INR));

            TransferResult result = attempt(cmd, shouldSucceed);
            if (result != null) {
                model[op.from()] -= op.amountMinor();
                model[op.to()] += op.amountMinor();
                if (op.retry()) {
                    TransferResult again = transfers.transfer(cmd);
                    assertThat(again.replayed()).isTrue();
                    assertThat(again.entryId()).isEqualTo(result.entryId());
                }
            }
        }

        String ids = accounts.stream().map(a -> "'" + a.getId() + "'").collect(Collectors.joining(","));
        long total = 0;
        for (int i = 0; i < ACCOUNTS; i++) {
            long actual = jdbc.queryForObject("SELECT balance_minor FROM account_balances WHERE account_id = ?",
                    Long.class, accounts.get(i).getId());
            assertThat(actual).as("balance of account %d matches the model", i).isEqualTo(model[i]);
            if (i > 0) {
                assertThat(actual).as("wallet %d never negative", i).isGreaterThanOrEqualTo(0);
            }
            long fromPostings = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END), 0) "
                            + "FROM postings WHERE account_id = ?", Long.class, accounts.get(i).getId());
            assertThat(fromPostings).as("balance of account %d equals its postings", i).isEqualTo(actual);
            total += actual;
        }
        assertThat(total).as("money is conserved across all accounts").isZero();

        long unbalancedEntries = jdbc.queryForObject(
                "SELECT count(*) FROM (SELECT entry_id FROM postings WHERE entry_id IN "
                        + "(SELECT entry_id FROM postings WHERE account_id IN (" + ids + ")) "
                        + "GROUP BY entry_id HAVING SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END) <> 0) x",
                Long.class);
        assertThat(unbalancedEntries).as("entries whose debits differ from credits").isZero();
    }

    /** Runs the transfer; expected business failures return null, anything unexpected fails the property. */
    private TransferResult attempt(TransferCommand cmd, boolean shouldSucceed) {
        try {
            TransferResult result = transfers.transfer(cmd);
            assertThat(shouldSucceed).as("transfer succeeded but the model expected a refusal").isTrue();
            return result;
        } catch (InsufficientFundsException | InvalidTransferException e) {
            assertThat(shouldSucceed).as("transfer refused (%s) but the model expected success", e.getMessage()).isFalse();
            return null;
        }
    }
}
