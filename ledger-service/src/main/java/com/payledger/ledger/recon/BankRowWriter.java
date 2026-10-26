package com.payledger.ledger.recon;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Step 1's writer: stages the file's rows. Idempotent per (run, line), so a restarted chunk cannot duplicate. */
@Component
public class BankRowWriter implements ItemWriter<BankRow> {

    private final JdbcTemplate jdbc;

    public BankRowWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void write(Chunk<? extends BankRow> chunk) {
        List<Object[]> batch = new ArrayList<>();
        for (BankRow row : chunk) {
            batch.add(new Object[]{row.runId(), row.lineNo(), row.externalRef(), row.amountMinor(), row.currency(),
                    row.settledAt() == null ? null : Timestamp.from(row.settledAt())});
        }
        jdbc.batchUpdate("INSERT INTO recon_bank_rows (run_id, line_no, external_ref, amount_minor, currency, settled_at) "
                + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING", batch);
    }
}
