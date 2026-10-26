package com.payledger.ledger.recon;

import com.payledger.ledger.recon.ReconExceptions.InvalidReconRequestException;
import com.payledger.ledger.recon.ReconExceptions.ReconRunInProgressException;
import com.payledger.ledger.recon.ReconExceptions.ReconRunNotFoundException;
import com.payledger.ledger.recon.ReconRunRepository.Item;
import com.payledger.ledger.recon.ReconRunRepository.Run;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Starts reconciliation runs (ADR 0017). A run is identified by the file's SHA-256 and the period: submitting the
 * same file for the same period again returns the existing result, and a failed run is restarted instead of redone.
 */
@Service
public class ReconService {

    /** {@code created} is false when the call was answered from an earlier run. */
    public record Submitted(Run run, boolean created) {
    }

    static final String HEADER = "external_ref,amount_minor,currency,settled_at";

    private final ReconRunRepository runs;
    private final JobLauncher launcher;
    private final Job job;
    private final Path storageDir;
    private final MeterRegistry metrics;

    public ReconService(ReconRunRepository runs, JobLauncher launcher, @Qualifier("reconJob") Job job,
                        @Value("${ledger.recon.storage-dir}") String storageDir, MeterRegistry metrics) {
        this.metrics = metrics;
        this.runs = runs;
        this.launcher = launcher;
        this.job = job;
        this.storageDir = Path.of(storageDir);
    }

    public Submitted submit(String fileName, byte[] content, Instant periodStart, Instant periodEnd) {
        byte[] normalized = normalize(content);
        if (!periodEnd.isAfter(periodStart)) {
            throw new InvalidReconRequestException("periodEnd must be after periodStart");
        }
        String sha256 = sha256(normalized);

        UUID candidate = UUID.randomUUID();
        boolean fresh = true;
        try {
            runs.insert(candidate, fileName, sha256, periodStart, periodEnd);
        } catch (DuplicateKeyException sameFileAndPeriod) {
            fresh = false;
        }
        Run run = fresh ? runs.find(candidate).orElseThrow()
                : runs.findByIdentity(sha256, periodStart, periodEnd).orElseThrow();
        if (!fresh) {
            switch (run.status()) {
                case "COMPLETED" -> {
                    return new Submitted(run, false);       // nothing to redo
                }
                case "RUNNING" -> throw new ReconRunInProgressException(run.id());
                default -> runs.markRunning(run.id());        // FAILED: restart the same job instance
            }
        }

        Path stored = store(sha256, normalized);
        JobParameters parameters = new JobParametersBuilder()
                .addString("sha256", sha256, true)               // identifying: with the period, this is the job instance
                .addString("periodStart", periodStart.toString(), true)
                .addString("periodEnd", periodEnd.toString(), true)
                .addString("runId", run.id().toString(), false)
                .addString("file", stored.toString(), false)
                .toJobParameters();
        try {
            JobExecution execution = launcher.run(job, parameters);
            if (execution.getStatus() != BatchStatus.COMPLETED) {
                runs.markFailed(run.id(), describeFailure(execution));
            }
        } catch (JobInstanceAlreadyCompleteException alreadyDone) {
            // the batch tables say it finished; the run row is the source of truth for the result
        } catch (JobExecutionAlreadyRunningException running) {
            throw new ReconRunInProgressException(run.id());
        } catch (Exception e) {
            runs.markFailed(run.id(), e.getMessage());
        }
        Run result = get(run.id());
        recordMetrics(result);
        return new Submitted(result, fresh);
    }

    /** Counts this call's own run only; answers served from an earlier run return before reaching here (ADR 0020). */
    private void recordMetrics(Run run) {
        metrics.counter("recon.runs", "status", run.status()).increment();
        if (!"COMPLETED".equals(run.status())) {
            return;
        }
        metrics.counter("recon.mismatches", "outcome", "MISSING_IN_LEDGER").increment(run.missingInLedger());
        metrics.counter("recon.mismatches", "outcome", "MISSING_IN_BANK").increment(run.missingInBank());
        metrics.counter("recon.mismatches", "outcome", "AMOUNT_MISMATCH").increment(run.amountMismatch());
        metrics.counter("recon.mismatches", "outcome", "DUPLICATE_IN_BANK").increment(run.duplicateInBank());
    }

    public List<Run> latest() {
        return runs.latest(50);
    }

    public Run get(UUID id) {
        return runs.find(id).orElseThrow(() -> new ReconRunNotFoundException(id));
    }

    public List<Item> items(UUID runId, String outcome, boolean includeMatched, int page, int size) {
        get(runId);
        return runs.items(runId, outcome, includeMatched, page, size);
    }

    /**
     * Trailing blank lines are harmless, so they are dropped before hashing (the same data always has the same
     * identity). The header must be exactly as agreed: a missing header would silently swallow the first data row.
     */
    private static byte[] normalize(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8).stripTrailing();
        if (text.isEmpty()) {
            throw new InvalidReconRequestException("the file is empty");
        }
        String header = text.lines().findFirst().orElse("").trim();
        if (!header.equals(HEADER)) {
            throw new InvalidReconRequestException("the first line must be the header " + HEADER);
        }
        return (text + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private Path store(String sha256, byte[] content) {
        try {
            Files.createDirectories(storageDir);
            Path file = storageDir.resolve(sha256 + ".csv");
            Files.write(file, content);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("cannot store the uploaded file", e);
        }
    }

    private static String describeFailure(JobExecution execution) {
        return execution.getAllFailureExceptions().stream().findFirst()
                .map(ReconService::rootMessage).orElse(execution.getExitStatus().getExitDescription());
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every JVM", e);
        }
    }
}
