package com.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import com.payledger.ledger.recon.BankRow;
import com.payledger.ledger.recon.BankRowWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.batch.item.Chunk;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** A job that dies part-way resumes from the last committed chunk and ends with exactly the right rows. */
@TestPropertySource(properties = "ledger.recon.chunk-size=10")
class ReconRestartTest extends ReconTestBase {

    @MockitoSpyBean BankRowWriter writer;

    @Test
    void aFailedRunIsRestartedWithoutDuplicatingOrLosingRows() throws Exception {
        Instant start = Instant.now().minusSeconds(5);
        List<Capture> captured = captures(100, 20_000);
        Instant end = Instant.now().plus(Duration.ofHours(1));
        List<String> rows = new ArrayList<>();
        captured.forEach(c -> rows.add(row(c.ref(), c.amountMinor(), "INR")));
        String file = csv(rows);

        // chunks 1-3 are written for real, the 4th write fails (a disk or connection error), the rest work again
        doCallRealMethod().doCallRealMethod().doCallRealMethod()
                .doThrow(new IllegalStateException("simulated failure in the 4th chunk"))
                .doCallRealMethod()
                .when(writer).write(Mockito.<Chunk<BankRow>>any());

        long instancesBefore = count("batch_job_instance");
        long executionsBefore = count("batch_job_execution");

        ResponseEntity<Map> first = upload(file, start, end);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(first.getBody().get("status")).isEqualTo("FAILED");
        assertThat(first.getBody().get("error").toString()).contains("simulated failure");
        Object runId = first.getBody().get("id");
        assertThat(count("recon_bank_rows")).as("three committed chunks survive the failure").isEqualTo(30);
        assertThat(count("recon_items")).isZero();

        ResponseEntity<Map> second = upload(file, start, end); // same file, same period: restart, not a new run

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("status")).isEqualTo("COMPLETED");
        assertThat(second.getBody().get("id")).as("same run").isEqualTo(runId);
        assertThat(count("recon_bank_rows")).as("every line staged exactly once").isEqualTo(100);
        assertThat(count("recon_items")).isEqualTo(100);
        assertThat(outcomes(runId).values()).allMatch("MATCHED"::equals);
        assertThat(count("recon_runs")).isEqualTo(1);
        assertThat(count("batch_job_instance") - instancesBefore).as("one job instance, restarted").isEqualTo(1);
        assertThat(count("batch_job_execution") - executionsBefore).as("failed attempt + restart").isEqualTo(2);
    }
}
