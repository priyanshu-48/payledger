package com.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ReconApiTest extends ReconTestBase {

    private final Instant start = Instant.now().minusSeconds(30);

    private Instant end() {
        return Instant.now().plus(Duration.ofHours(1));
    }

    @Test
    void aRunReportsCountersAndItsMismatchesOnlyByDefault() {
        List<Capture> c = captures(3, 1_000);
        String file = csv(List.of(row(c.get(0).ref(), 1_000, "INR"), row(c.get(1).ref(), 999, "INR"),
                row("payment-capture:unknown", 5, "INR")));

        ResponseEntity<Map> created = upload(file, start, end());

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("status", "COMPLETED").containsEntry("matched", 1)
                .containsEntry("amountMismatch", 1).containsEntry("missingInLedger", 1).containsEntry("missingInBank", 1);
        String runId = (String) created.getBody().get("id");

        assertThat(http.getForObject("/recon/runs/{id}", Map.class, runId)).containsEntry("matched", 1);
        List<Map<String, Object>> mismatches = http.getForObject("/recon/runs/{id}/items", List.class, runId);
        assertThat(mismatches).extracting(i -> i.get("outcome"))
                .containsExactlyInAnyOrder("AMOUNT_MISMATCH", "MISSING_IN_LEDGER", "MISSING_IN_BANK");
        List<Map<String, Object>> everything = http.getForObject("/recon/runs/{id}/items?includeMatched=true", List.class, runId);
        assertThat(everything).hasSize(4);
        List<Map<String, Object>> onlyMatched = http.getForObject("/recon/runs/{id}/items?outcome=MATCHED", List.class, runId);
        assertThat(onlyMatched).hasSize(1);
        Map<String, Object> mismatch = mismatches.stream().filter(i -> i.get("outcome").equals("AMOUNT_MISMATCH")).findFirst().orElseThrow();
        assertThat(mismatch).containsEntry("ledgerAmountMinor", 1_001).containsEntry("bankAmountMinor", 999);
        assertThat(mismatch.get("detail").toString()).contains("ledger 1001 INR vs bank 999 INR");
    }

    @Test
    void paging() {
        List<Capture> c = captures(5, 100);
        ResponseEntity<Map> created = upload(csv(List.of()), start, end());
        String runId = (String) created.getBody().get("id");

        List<Map<String, Object>> first = http.getForObject("/recon/runs/{id}/items?size=2&page=0", List.class, runId);
        List<Map<String, Object>> second = http.getForObject("/recon/runs/{id}/items?size=2&page=1", List.class, runId);
        List<Map<String, Object>> third = http.getForObject("/recon/runs/{id}/items?size=2&page=2", List.class, runId);

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(2);
        assertThat(third).hasSize(1);
        assertThat(first).doesNotContainAnyElementsOf(second);
        assertThat(c).hasSize(5);
    }

    @Test
    void submittingTheSameFileAndPeriodAgainChangesNothing() {
        List<Capture> c = captures(4, 500);
        String file = csv(List.of(row(c.get(0).ref(), 500, "INR"), row(c.get(1).ref(), 999, "INR")));
        Instant end = end();

        ResponseEntity<Map> first = upload(file, start, end);
        long items = count("recon_items");
        ResponseEntity<Map> again = upload(file, start, end);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(count("recon_items")).isEqualTo(items);
        assertThat(count("recon_runs")).isEqualTo(1);
        assertThat(count("recon_bank_rows")).isEqualTo(2);
    }

    @Test
    void theSameFileForAnotherPeriodIsANewRun() {
        List<Capture> c = captures(2, 500);
        String file = csv(List.of(row(c.get(0).ref(), 500, "INR")));

        ResponseEntity<Map> first = upload(file, start, end());
        ResponseEntity<Map> other = upload(file, start.minusSeconds(60), end());

        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(other.getBody().get("id")).isNotEqualTo(first.getBody().get("id"));
    }

    @Test
    void aMalformedRowFailsTheRunLoudlyAndACorrectedFileSucceeds() {
        List<Capture> c = captures(2, 500);
        String broken = csv(List.of(row(c.get(0).ref(), 500, "INR"), c.get(1).ref() + ",not-a-number,INR,2026-10-26T10:00:00Z"));

        ResponseEntity<Map> failed = upload(broken, start, end());

        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(failed.getBody()).containsEntry("status", "FAILED");
        assertThat(failed.getBody().get("error").toString()).contains("not-a-number");
        assertThat(count("recon_items")).isZero();

        String fixed = csv(List.of(row(c.get(0).ref(), 500, "INR"), row(c.get(1).ref(), 501, "INR")));
        ResponseEntity<Map> ok = upload(fixed, start, end());
        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(ok.getBody()).containsEntry("status", "COMPLETED").containsEntry("matched", 2);
    }

    @Test
    void edgeCasesInTheComparisonRules() {
        List<Capture> c = captures(2, 700);
        String file = csv(List.of(
                row("payment-capture:ghost", 1, "INR"), row("payment-capture:ghost", 1, "INR"), // duplicate AND unknown to the ledger
                row(c.get(0).ref(), 700, "USD"),                                                  // same amount, other currency
                row(c.get(1).ref(), 701, "INR")));

        ResponseEntity<Map> run = upload(file, start, end());

        Map<String, String> outcomes = outcomes(run.getBody().get("id"));
        assertThat(outcomes.get("payment-capture:ghost")).as("no ledger entry wins over duplicate").isEqualTo("MISSING_IN_LEDGER");
        assertThat(outcomes.get(c.get(0).ref())).isEqualTo("AMOUNT_MISMATCH");
        assertThat(outcomes.get(c.get(1).ref())).isEqualTo("MATCHED");
    }

    @Test
    void badRequestsAndUnknownRuns() {
        assertThat(http.getForEntity("/recon/runs/{id}", Map.class, UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/recon/runs/{id}/items", Map.class, UUID.randomUUID()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/recon/runs/not-a-uuid", Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(upload(csv(List.of()), end(), start).getStatusCode()).as("period end before start")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(upload(new byte[0], start, end()).getStatusCode()).as("empty file").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.postForEntity("/recon/runs?periodStart={s}&periodEnd={e}", null, Map.class, start, end())
                .getStatusCode()).as("no multipart body").isIn(HttpStatus.BAD_REQUEST, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }
}
