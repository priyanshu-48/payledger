package com.payledger.ledger;

import com.payledger.common.Money;
import com.payledger.ledger.domain.EntryType;
import com.payledger.ledger.service.LedgerService;
import com.payledger.ledger.service.TransferCommand;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/** Shared setup for the reconciliation tests: a funded payer, a merchant, captured payments and CSV upload. */
abstract class ReconTestBase extends ApiTestSupport {

    /** What the ledger holds for one capture: the reference the bank is told and the amount posted. */
    record Capture(String ref, long amountMinor) {
    }

    @Autowired LedgerService ledger;

    UUID payer;
    UUID merchant;

    @BeforeEach
    void reconSetup() {
        jdbc.execute("TRUNCATE recon_items, recon_bank_rows, recon_runs CASCADE");
        UUID settlement = createAccount("SETTLEMENT", "INR");
        payer = fundedWallet(settlement, "1000000.00");
        merchant = createAccount("MERCHANT", "INR");
    }

    /** Posts PAYMENT_CAPTURE entries exactly as the payment-event consumer does. */
    List<Capture> captures(int count, long firstAmountMinor) {
        List<Capture> made = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String ref = "payment-capture:" + UUID.randomUUID();
            long amount = firstAmountMinor + i;
            ledger.transfer(new TransferCommand(ref, payer, merchant, Money.of(amount, "INR"), EntryType.PAYMENT_CAPTURE));
            made.add(new Capture(ref, amount));
        }
        return made;
    }

    static String row(String ref, long amountMinor, String currency) {
        return ref + "," + amountMinor + "," + currency + ",2026-10-26T10:00:00Z";
    }

    static String csv(List<String> rows) {
        return "external_ref,amount_minor,currency,settled_at\n" + String.join("\n", rows) + "\n";
    }

    ResponseEntity<Map> upload(String csv, Instant start, Instant end) {
        return upload(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8), start, end);
    }

    ResponseEntity<Map> upload(byte[] bytes, Instant start, Instant end) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "settlement.csv";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.exchange("/recon/runs?periodStart={s}&periodEnd={e}", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class, start.toString(), end.toString());
    }

    /** All items of a run as ref -> outcome, read straight from the table. */
    Map<String, String> outcomes(Object runId) {
        Map<String, String> result = new java.util.HashMap<>();
        jdbc.query("SELECT external_ref, outcome FROM recon_items WHERE run_id = ?::uuid",
                rs -> {
                    result.put(rs.getString(1), rs.getString(2));
                }, runId.toString());
        return result;
    }
}
