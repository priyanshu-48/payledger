package com.payledger.ledger.recon;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Plain SQL over recon_runs / recon_items; the batch steps write the items, this class reads them and tracks runs. */
@Repository
public class ReconRunRepository {

    public record Run(UUID id, String fileName, String fileSha256, Instant periodStart, Instant periodEnd, String status,
                      Instant startedAt, Instant finishedAt, int matched, int missingInLedger, int missingInBank,
                      int amountMismatch, int duplicateInBank, String error) {
    }

    public record Item(String externalRef, String outcome, Long ledgerAmountMinor, Long bankAmountMinor, String detail) {
    }

    private static final RowMapper<Run> RUN = (rs, i) -> new Run(rs.getObject("id", UUID.class),
            rs.getString("file_name"), rs.getString("file_sha256"), instant(rs, "period_start"), instant(rs, "period_end"),
            rs.getString("status"), instant(rs, "started_at"), instant(rs, "finished_at"), rs.getInt("matched"),
            rs.getInt("missing_in_ledger"), rs.getInt("missing_in_bank"), rs.getInt("amount_mismatch"),
            rs.getInt("duplicate_in_bank"), rs.getString("error"));

    private final JdbcTemplate jdbc;

    public ReconRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Run> find(UUID id) {
        return jdbc.query("SELECT * FROM recon_runs WHERE id = ?", RUN, id).stream().findFirst();
    }

    public Optional<Run> findByIdentity(String sha256, Instant start, Instant end) {
        return jdbc.query("SELECT * FROM recon_runs WHERE file_sha256 = ? AND period_start = ? AND period_end = ?",
                RUN, sha256, utc(start), utc(end)).stream().findFirst();
    }

    /** Throws DuplicateKeyException if this (file, period) already has a run. */
    public void insert(UUID id, String fileName, String sha256, Instant start, Instant end) {
        jdbc.update("INSERT INTO recon_runs (id, file_name, file_sha256, period_start, period_end, status) "
                + "VALUES (?, ?, ?, ?, ?, 'RUNNING')", id, fileName, sha256, utc(start), utc(end));
    }

    public void markRunning(UUID id) {
        jdbc.update("UPDATE recon_runs SET status = 'RUNNING', error = NULL, finished_at = NULL WHERE id = ?", id);
    }

    public void markFailed(UUID id, String error) {
        jdbc.update("UPDATE recon_runs SET status = 'FAILED', finished_at = now(), error = ? WHERE id = ?", error, id);
    }

    /** Items of a run, ordered by reference. MATCHED rows are listed only when asked for (or filtered to explicitly). */
    public List<Item> items(UUID runId, String outcome, boolean includeMatched, int page, int size) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT external_ref, outcome, ledger_amount, bank_amount, detail "
                + "FROM recon_items WHERE run_id = ?");
        args.add(runId);
        if (outcome != null) {
            sql.append(" AND outcome = ?");
            args.add(outcome);
        } else if (!includeMatched) {
            sql.append(" AND outcome <> 'MATCHED'");
        }
        sql.append(" ORDER BY external_ref LIMIT ? OFFSET ?");
        args.add(size);
        args.add((long) page * size);
        return jdbc.query(sql.toString(), (rs, i) -> new Item(rs.getString("external_ref"), rs.getString("outcome"),
                (Long) rs.getObject("ledger_amount"), (Long) rs.getObject("bank_amount"), rs.getString("detail")),
                args.toArray());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
