package com.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

/** A reconciliation run adds exactly its mismatches to recon.mismatches; an answer served from an old run adds nothing. */
class ReconMetricsTest extends ReconTestBase {

    @Autowired MeterRegistry registry;

    private double mismatches(String outcome) {
        var c = registry.find("recon.mismatches").tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    private double runs(String status) {
        var c = registry.find("recon.runs").tag("status", status).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void mismatchesAndRunsAreCountedOncePerRun() throws Exception {
        Thread.sleep(50);
        Instant start = Instant.now();
        Thread.sleep(50);
        List<Capture> made = captures(6, 10_000);
        Instant end = Instant.now().plus(Duration.ofHours(1));
        double missingInBank = mismatches("MISSING_IN_BANK");
        double amount = mismatches("AMOUNT_MISMATCH");
        double ghosts = mismatches("MISSING_IN_LEDGER");
        double completed = runs("COMPLETED");

        List<String> rows = List.of(
                row(made.get(0).ref(), made.get(0).amountMinor(), "INR"),
                row(made.get(1).ref(), made.get(1).amountMinor(), "INR"),
                row(made.get(2).ref(), made.get(2).amountMinor() + 1, "INR"),    // amount differs
                row(made.get(3).ref(), made.get(3).amountMinor() + 1, "INR"),    // amount differs
                row("payment-capture:ghost-1", 5, "INR"));                         // 4 and 5 are missing in the bank file
        String csv = csv(rows);

        ResponseEntity<Map> first = upload(csv, start, end);
        ResponseEntity<Map> again = upload(csv, start, end);

        assertThat(first.getBody().get("status")).isEqualTo("COMPLETED");
        assertThat(again.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(mismatches("MISSING_IN_BANK") - missingInBank).isEqualTo(2);
        assertThat(mismatches("AMOUNT_MISMATCH") - amount).isEqualTo(2);
        assertThat(mismatches("MISSING_IN_LEDGER") - ghosts).isEqualTo(1);
        assertThat(runs("COMPLETED") - completed).as("the repeat was not a new run").isEqualTo(1);
    }
}
