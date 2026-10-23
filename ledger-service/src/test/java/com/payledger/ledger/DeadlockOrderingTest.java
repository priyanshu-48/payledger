package com.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Why transfer() locks in sorted order (ADR 0007): plain JDBC, no ledger code. Two transactions that lock
 * the same two rows in opposite order deadlock; locking in the same order cannot.
 */
class DeadlockOrderingTest extends AbstractPostgresTest {

    private static final String DEADLOCK = "40P01";

    @BeforeAll
    static void createRows() throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS lock_demo (id INT PRIMARY KEY)");
            s.execute("INSERT INTO lock_demo VALUES (1), (2) ON CONFLICT DO NOTHING");
            c.commit(); // connect() turns auto-commit off
        }
    }

    static Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        c.setAutoCommit(false);
        return c;
    }

    /** Locks {@code first}, optionally waits for the other thread to hold its first lock, then locks {@code second}. */
    private Callable<String> lockInOrder(int first, int second, CyclicBarrier bothHoldFirst) {
        return () -> {
            try (Connection c = connect(); Statement s = c.createStatement()) {
                s.execute("SELECT id FROM lock_demo WHERE id = " + first + " FOR UPDATE");
                if (bothHoldFirst != null) {
                    bothHoldFirst.await();
                }
                s.execute("SELECT id FROM lock_demo WHERE id = " + second + " FOR UPDATE");
                c.commit();
                return "ok";
            } catch (SQLException e) {
                return e.getSQLState();
            }
        };
    }

    private List<String> run(Callable<String> a, Callable<String> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<String> out = new ArrayList<>();
            for (Future<String> f : pool.invokeAll(List.of(a, b))) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void oppositeLockOrderDeadlocks() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<String> outcomes = run(lockInOrder(1, 2, barrier), lockInOrder(2, 1, barrier));

        assertThat(outcomes).contains(DEADLOCK); // Postgres aborts one of them
    }

    @Test
    void sameLockOrderNeverDeadlocks() throws Exception {
        for (int i = 0; i < 20; i++) {
            List<String> outcomes = run(lockInOrder(1, 2, null), lockInOrder(1, 2, null));
            assertThat(outcomes).containsExactly("ok", "ok");
        }
    }
}
