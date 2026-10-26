package com.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * SPEC section 7 reconciliation: a bank file with planted errors. Every planted mismatch must be found with
 * the right outcome, and nothing else may be flagged (0 false positives).
 */
class ReconciliationTest extends ReconTestBase {

    @Test
    void everyPlantedMismatchIsFoundAndNothingElseIsFlagged() throws Exception {
        // 20 captures BEFORE the period: some in the bank file (fine), some not (must not be reported as missing)
        List<Capture> old = captures(20, 5_000);
        Thread.sleep(50);
        Instant periodStart = Instant.now();
        Thread.sleep(50);
        List<Capture> inPeriod = captures(200, 10_000);
        transfer("plain-transfer-1", payer, merchant, "1.00", "INR"); // a TRANSFER entry: not a capture, never compared
        Instant periodEnd = Instant.now().plus(Duration.ofHours(1));

        Map<String, String> expected = new HashMap<>();
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < old.size(); i++) {
            if (i < 17) {                                            // 17 of the old ones are listed, 3 are not
                rows.add(row(old.get(i).ref(), old.get(i).amountMinor(), "INR"));
                expected.put(old.get(i).ref(), "MATCHED");
            }
        }
        for (int i = 0; i < inPeriod.size(); i++) {
            Capture c = inPeriod.get(i);
            if (i < 5) {                                              // planted: dropped from the bank file
                expected.put(c.ref(), "MISSING_IN_BANK");
            } else if (i < 10) {                                      // planted: bank amount differs
                rows.add(row(c.ref(), c.amountMinor() + 1, "INR"));
                expected.put(c.ref(), "AMOUNT_MISMATCH");
            } else if (i < 12) {                                      // planted: bank currency differs
                rows.add(row(c.ref(), c.amountMinor(), "USD"));
                expected.put(c.ref(), "AMOUNT_MISMATCH");
            } else if (i < 15) {                                      // planted: bank lists it twice
                rows.add(row(c.ref(), c.amountMinor(), "INR"));
                rows.add(row(c.ref(), c.amountMinor(), "INR"));
                expected.put(c.ref(), "DUPLICATE_IN_BANK");
            } else {
                rows.add(row(c.ref(), c.amountMinor(), "INR"));
                expected.put(c.ref(), "MATCHED");
            }
        }
        for (int i = 1; i <= 4; i++) {                                // planted: bank has captures we never made
            String ghost = "payment-capture:ghost-" + i;
            rows.add(row(ghost, 777, "INR"));
            expected.put(ghost, "MISSING_IN_LEDGER");
        }

        ResponseEntity<Map> response = upload(csv(rows), periodStart, periodEnd);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("status")).isEqualTo("COMPLETED");
        Object runId = response.getBody().get("id");
        assertThat(outcomes(runId)).as("exactly the planted outcomes, no more, no fewer").isEqualTo(expected);
        assertThat(response.getBody()).containsEntry("matched", 17 + 185)
                .containsEntry("missingInLedger", 4).containsEntry("missingInBank", 5)
                .containsEntry("amountMismatch", 7).containsEntry("duplicateInBank", 3);
        System.out.println("RECON: planted 5 missing-in-bank, 7 amount mismatches, 3 duplicates, 4 missing-in-ledger"
                + " -> found " + response.getBody().get("missingInBank") + "/" + response.getBody().get("amountMismatch")
                + "/" + response.getBody().get("duplicateInBank") + "/" + response.getBody().get("missingInLedger")
                + ", matched " + response.getBody().get("matched") + ", items total " + outcomes(runId).size());
    }
}
