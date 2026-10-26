package com.payledger.ledger.recon;

import com.payledger.ledger.recon.ReconRunRepository.Item;
import com.payledger.ledger.recon.ReconRunRepository.Run;
import com.payledger.ledger.recon.ReconService.Submitted;
import com.payledger.security.AuditLog;
import com.payledger.security.Caller;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Reconciliation API. ADMIN-only once security lands (M7). */
@RestController
class ReconController {

    record RunResponse(UUID id, String fileName, String status, Instant periodStart, Instant periodEnd,
                       Instant startedAt, Instant finishedAt, int matched, int missingInLedger, int missingInBank,
                       int amountMismatch, int duplicateInBank, String error) {
        static RunResponse of(Run r) {
            return new RunResponse(r.id(), r.fileName(), r.status(), r.periodStart(), r.periodEnd(), r.startedAt(),
                    r.finishedAt(), r.matched(), r.missingInLedger(), r.missingInBank(), r.amountMismatch(),
                    r.duplicateInBank(), r.error());
        }
    }

    record ItemResponse(String externalRef, String outcome, Long ledgerAmountMinor, Long bankAmountMinor, String detail) {
        static ItemResponse of(Item i) {
            return new ItemResponse(i.externalRef(), i.outcome(), i.ledgerAmountMinor(), i.bankAmountMinor(), i.detail());
        }
    }

    private static final int MAX_PAGE_SIZE = 1000;

    private final ReconService service;
    private final AuditLog audit;

    ReconController(ReconService service, AuditLog audit) {
        this.service = service;
        this.audit = audit;
    }

    /**
     * 201 for a new run, 200 when this file and period were reconciled before, 422 when the run failed
     * (for example a malformed row); the body is the run in every case.
     */
    @PostMapping(path = "/recon/runs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<RunResponse> start(@RequestParam("file") MultipartFile file,
                                      @RequestParam("periodStart") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant periodStart,
                                      @RequestParam("periodEnd") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant periodEnd,
                                      Authentication authentication)
            throws IOException {
        Submitted submitted = service.submit(file.getOriginalFilename() == null ? "upload.csv" : file.getOriginalFilename(),
                file.getBytes(), periodStart, periodEnd);
        if (submitted.created()) {
            audit.record(Caller.from(authentication).subject(), "recon.run", submitted.run().id().toString(),
                    Map.of("file", submitted.run().fileName(), "status", submitted.run().status()));
        }
        HttpStatus status = "FAILED".equals(submitted.run().status()) ? HttpStatus.UNPROCESSABLE_ENTITY
                : submitted.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(RunResponse.of(submitted.run()));
    }

    @GetMapping("/recon/runs")
    List<RunResponse> runs() {
        return service.latest().stream().map(RunResponse::of).toList();
    }

    @GetMapping("/recon/runs/{id}")
    RunResponse run(@PathVariable UUID id) {
        return RunResponse.of(service.get(id));
    }

    /** Mismatches by default; {@code outcome=MATCHED} or {@code includeMatched=true} to see matches too. */
    @GetMapping("/recon/runs/{id}/items")
    List<ItemResponse> items(@PathVariable UUID id,
                             @RequestParam(required = false) String outcome,
                             @RequestParam(defaultValue = "false") boolean includeMatched,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "100") int size) {
        return service.items(id, outcome, includeMatched, Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE))
                .stream().map(ItemResponse::of).toList();
    }
}
