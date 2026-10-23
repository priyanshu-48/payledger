package com.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.payledger.common.Money;
import com.payledger.ledger.domain.Account;
import com.payledger.ledger.domain.AccountType;
import com.payledger.ledger.service.LedgerExceptions.TransferContentionException;
import com.payledger.ledger.service.LedgerService;
import com.payledger.ledger.service.LockingStrategy;
import com.payledger.ledger.service.OptimisticTransferExecutor;
import com.payledger.ledger.service.TransferCommand;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SPEC section 7 concurrency test and the M2 benchmark. 64 threads call the transfer strategies directly
 * (no HTTP, so locking is what gets measured) over 20 accounts that include a hot one. Expected balances
 * are computed from the transfers that reported success, so any lost or doubled update shows up as drift.
 * Default size is a correctness gate; -Dbench=true runs 3 repeats at 3x the size for the ADR numbers.
 */
class ConcurrencyHarnessTest extends AbstractPostgresTest {

    private static final int THREADS = 64;
    private static final int POOL_SIZE = 64;
    private static final int ACCOUNTS = 20; // 0 = SETTLEMENT (funds everyone), 1..19 = wallets
    private static final int HOT = 1;
    private static final long FUNDING = 1_000_000;
    private static final boolean BENCH = Boolean.getBoolean("bench");
    private static final int REPEATS = BENCH ? 3 : 1;
    private static final int SCALE = BENCH ? 3 : 1;

    private static ConfigurableApplicationContext context;
    private static LedgerService pessimistic;
    private static OptimisticTransferExecutor optimistic;
    private static JdbcTemplate jdbc;

    record Task(int from, int to, long amount) {
    }

    record Scenario(String name, int transfers, BiFunction<Random, Integer, Task> generator) {
    }

    record Run(String scenario, LockingStrategy strategy, int repeat, int transfers, int ok, int failed,
               double tps, long p50, long p95, long p99, long retries, long drift, long deadlocks) {
    }

    @BeforeAll
    static void start() {
        context = new SpringApplicationBuilder(LedgerApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--spring.datasource.hikari.maximum-pool-size=" + POOL_SIZE);
        pessimistic = context.getBean(LedgerService.class);
        optimistic = context.getBean(OptimisticTransferExecutor.class);
        jdbc = context.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void stop() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void bothStrategiesStayCorrectUnderContention() throws Exception {
        List<Scenario> scenarios = List.of(
                new Scenario("A spread + hot account", 1_000 * SCALE, ConcurrencyHarnessTest::spreadWithHot),
                new Scenario("B hot receiver", 2_000 * SCALE, ConcurrencyHarnessTest::hotReceiver));
        List<Run> runs = new ArrayList<>();
        try {
            for (Scenario scenario : scenarios) {
                for (LockingStrategy strategy : LockingStrategy.values()) {
                    for (int repeat = 1; repeat <= REPEATS; repeat++) {
                        runs.add(execute(scenario, strategy, repeat));
                    }
                }
            }
        } finally {
            report(runs);
        }
    }

    private Run execute(Scenario scenario, LockingStrategy strategy, int repeat) throws Exception {
        String tag = newTag();
        List<Account> accounts = new ArrayList<>();
        accounts.add(pessimistic.createAccount("settlement", AccountType.SETTLEMENT, "INR"));
        for (int i = 1; i < ACCOUNTS; i++) {
            accounts.add(pessimistic.createAccount("wallet-" + i, AccountType.USER_WALLET, "INR"));
        }
        for (int i = 1; i < ACCOUNTS; i++) {
            pessimistic.transfer(command("fund-" + tag + "-" + i, accounts, 0, i, FUNDING));
        }

        Random random = new Random(42L + repeat);
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < scenario.transfers(); i++) {
            tasks.add(scenario.generator().apply(random, i));
        }

        long deadlocksBefore = deadlocks();
        long retriesBefore = optimistic.retryCount();
        List<Callable<long[]>> calls = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            Task task = tasks.get(i);
            TransferCommand cmd = command("t-" + tag + "-" + i, accounts, task.from(), task.to(), task.amount());
            calls.add(() -> timed(strategy, cmd));
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<long[]> outcomes = new ArrayList<>();
        long t0 = System.nanoTime();
        try {
            for (Future<long[]> f : pool.invokeAll(calls)) {
                outcomes.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        double seconds = (System.nanoTime() - t0) / 1e9;
        long retries = optimistic.retryCount() - retriesBefore;
        long deadlockDelta = deadlocks() - deadlocksBefore;

        // outcome[0]: 1 ok, 0 gave up (contention); outcome[1]: latency in ns
        long[] expected = new long[ACCOUNTS];
        for (int i = 1; i < ACCOUNTS; i++) {
            expected[i] = FUNDING;
            expected[0] -= FUNDING;
        }
        int ok = 0;
        for (int i = 0; i < tasks.size(); i++) {
            if (outcomes.get(i)[0] == 1) {
                ok++;
                expected[tasks.get(i).from()] -= tasks.get(i).amount();
                expected[tasks.get(i).to()] += tasks.get(i).amount();
            }
        }

        long drift = 0;
        long total = 0;
        for (int i = 0; i < ACCOUNTS; i++) {
            UUID id = accounts.get(i).getId();
            long actual = jdbc.queryForObject("SELECT balance_minor FROM account_balances WHERE account_id = ?",
                    Long.class, id);
            long fromPostings = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount_minor ELSE -amount_minor END), 0) "
                            + "FROM postings WHERE account_id = ?", Long.class, id);
            assertThat(fromPostings).as("account %d: balance equals its postings", i).isEqualTo(actual);
            drift += Math.abs(actual - expected[i]);
            total += actual;
        }
        String ids = accounts.stream().map(a -> "'" + a.getId() + "'").collect(Collectors.joining(","));
        long entries = jdbc.queryForObject(
                "SELECT count(DISTINCT entry_id) FROM postings WHERE account_id IN (" + ids + ")", Long.class);

        assertThat(drift).as("%s/%s balance drift", scenario.name(), strategy).isZero();
        assertThat(total).as("money is conserved").isZero();
        assertThat(deadlockDelta).as("database deadlocks").isZero();
        assertThat(entries).as("one journal entry per successful transfer plus funding").isEqualTo(ok + ACCOUNTS - 1);
        if (strategy == LockingStrategy.PESSIMISTIC) {
            assertThat(ok).as("pessimistic never gives up").isEqualTo(tasks.size());
        }

        long[] latencies = outcomes.stream().mapToLong(o -> o[1]).sorted().toArray();
        return new Run(scenario.name(), strategy, repeat, tasks.size(), ok, tasks.size() - ok, ok / seconds,
                percentile(latencies, 0.50), percentile(latencies, 0.95), percentile(latencies, 0.99),
                retries, drift, deadlockDelta);
    }

    /** Returns {1 = ok / 0 = gave up, latency ns}. Any other failure is a test bug and propagates. */
    private long[] timed(LockingStrategy strategy, TransferCommand cmd) {
        long start = System.nanoTime();
        try {
            if (strategy == LockingStrategy.PESSIMISTIC) {
                pessimistic.transfer(cmd);
            } else {
                optimistic.transfer(cmd);
            }
            return new long[]{1, System.nanoTime() - start};
        } catch (TransferContentionException e) {
            return new long[]{0, System.nanoTime() - start};
        }
    }

    private static TransferCommand command(String key, List<Account> accounts, int from, int to, long amount) {
        return new TransferCommand(key, accounts.get(from).getId(), accounts.get(to).getId(), Money.of(amount, "INR"));
    }

    private static Task spreadWithHot(Random r, int i) {
        int from;
        int to;
        if (r.nextDouble() < 0.3) { // 30% of transfers touch the hot account, half as payer, half as payee
            int other = otherWallet(r, HOT);
            if (r.nextBoolean()) {
                from = HOT;
                to = other;
            } else {
                from = other;
                to = HOT;
            }
        } else {
            from = 1 + r.nextInt(ACCOUNTS - 1);
            to = otherWallet(r, from);
        }
        return new Task(from, to, 1 + r.nextInt(500));
    }

    private static Task hotReceiver(Random r, int i) {
        int from = otherWallet(r, HOT);
        return new Task(from, HOT, 1 + r.nextInt(100));
    }

    private static int otherWallet(Random r, int not) {
        int w;
        do {
            w = 1 + r.nextInt(ACCOUNTS - 1);
        } while (w == not);
        return w;
    }

    /** pg_stat counters are flushed lazily, so wait before reading. */
    private long deadlocks() throws InterruptedException {
        Thread.sleep(1500);
        return jdbc.queryForObject("SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()", Long.class);
    }

    private static long percentile(long[] sortedNanos, double p) {
        int index = (int) Math.min(sortedNanos.length - 1, Math.ceil(p * sortedNanos.length) - 1);
        return sortedNanos[Math.max(index, 0)] / 1_000_000;
    }

    private static String newTag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void report(List<Run> runs) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("Concurrency harness: ").append(THREADS).append(" threads, ").append(ACCOUNTS)
                .append(" accounts, Hikari pool ").append(POOL_SIZE).append(BENCH ? ", BENCH mode (3 repeats, 3x size)" : ", gate mode")
                .append("\n\n| scenario | strategy | run | transfers | ok | gave up | transfers/s | p50 ms | p95 ms | p99 ms | retries | drift | deadlocks |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Run r : runs) {
            md.append(String.format("| %s | %s | %d | %d | %d | %d | %.0f | %d | %d | %d | %d | %d | %d |%n",
                    r.scenario(), r.strategy(), r.repeat(), r.transfers(), r.ok(), r.failed(), r.tps(),
                    r.p50(), r.p95(), r.p99(), r.retries(), r.drift(), r.deadlocks()));
        }
        Map<String, List<Double>> byCell = new TreeMap<>();
        for (Run r : runs) {
            byCell.computeIfAbsent(r.scenario() + " | " + r.strategy(), k -> new ArrayList<>()).add(r.tps());
        }
        md.append("\nMedian transfers/s per scenario and strategy:\n\n| scenario | strategy | median transfers/s |\n|---|---|---|\n");
        byCell.forEach((cell, values) -> {
            double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            md.append(String.format("| %s | %.0f |%n", cell, sorted[sorted.length / 2]));
        });
        System.out.println(md);
        Path out = Path.of("target", "concurrency-results.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString());
    }
}
