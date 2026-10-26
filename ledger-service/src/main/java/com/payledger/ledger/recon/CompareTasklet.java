package com.payledger.ledger.recon;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Step 2: the whole comparison as one set-based statement (ADR 0017). Part A looks up every bank reference in
 * the ledger (any time: a bank row for an old capture is not "missing"); part B lists ledger captures created in
 * the period that the bank file does not mention. ON CONFLICT DO NOTHING makes a re-run harmless.
 */
@Component
public class CompareTasklet implements Tasklet {

    static final String SQL = """
            INSERT INTO recon_items (run_id, external_ref, outcome, ledger_amount, bank_amount, detail)
            SELECT :run, b.external_ref,
                   CASE WHEN l.external_ref IS NULL THEN 'MISSING_IN_LEDGER'
                        WHEN b.n > 1 THEN 'DUPLICATE_IN_BANK'
                        WHEN l.amount <> b.amount OR l.currency <> b.currency THEN 'AMOUNT_MISMATCH'
                        ELSE 'MATCHED' END,
                   l.amount, b.amount,
                   CASE WHEN l.external_ref IS NULL THEN 'bank lists a capture the ledger does not have'
                        WHEN b.n > 1 THEN 'bank lists this reference ' || b.n || ' times'
                        WHEN l.amount <> b.amount OR l.currency <> b.currency
                             THEN 'ledger ' || l.amount || ' ' || l.currency || ' vs bank ' || b.amount || ' ' || b.currency
                        END
            FROM (SELECT external_ref,
                         (array_agg(amount_minor ORDER BY line_no))[1] AS amount,
                         (array_agg(currency ORDER BY line_no))[1] AS currency,
                         count(*) AS n
                  FROM recon_bank_rows WHERE run_id = :run GROUP BY external_ref) b
            LEFT JOIN (SELECT e.external_ref, p.amount_minor AS amount, p.currency
                       FROM journal_entries e
                       JOIN postings p ON p.entry_id = e.id AND p.direction = 'CREDIT'
                       WHERE e.type = 'PAYMENT_CAPTURE') l ON l.external_ref = b.external_ref
            UNION ALL
            SELECT :run, l.external_ref, 'MISSING_IN_BANK', l.amount, NULL::bigint,
                   'captured in the period but not in the bank file'
            FROM (SELECT e.external_ref, p.amount_minor AS amount
                  FROM journal_entries e
                  JOIN postings p ON p.entry_id = e.id AND p.direction = 'CREDIT'
                  WHERE e.type = 'PAYMENT_CAPTURE' AND e.created_at >= :start AND e.created_at < :end) l
            WHERE NOT EXISTS (SELECT 1 FROM recon_bank_rows r WHERE r.run_id = :run AND r.external_ref = l.external_ref)
            ON CONFLICT (run_id, external_ref) DO NOTHING
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public CompareTasklet(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Map<String, Object> params = chunkContext.getStepContext().getJobParameters();
        int inserted = jdbc.update(SQL, new MapSqlParameterSource()
                .addValue("run", UUID.fromString((String) params.get("runId")))
                .addValue("start", utc(Instant.parse((String) params.get("periodStart"))))
                .addValue("end", utc(Instant.parse((String) params.get("periodEnd")))));
        contribution.incrementWriteCount(inserted);
        return RepeatStatus.FINISHED;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
