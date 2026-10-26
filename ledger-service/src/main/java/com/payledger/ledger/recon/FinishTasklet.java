package com.payledger.ledger.recon;

import java.util.UUID;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Step 3: the run's counters come from the items actually stored, then the run is marked COMPLETED. */
@Component
public class FinishTasklet implements Tasklet {

    private final JdbcTemplate jdbc;

    public FinishTasklet(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        UUID runId = UUID.fromString((String) chunkContext.getStepContext().getJobParameters().get("runId"));
        jdbc.update("""
                UPDATE recon_runs r SET status = 'COMPLETED', finished_at = now(), error = NULL,
                    matched           = (SELECT count(*) FROM recon_items i WHERE i.run_id = r.id AND i.outcome = 'MATCHED'),
                    missing_in_ledger = (SELECT count(*) FROM recon_items i WHERE i.run_id = r.id AND i.outcome = 'MISSING_IN_LEDGER'),
                    missing_in_bank   = (SELECT count(*) FROM recon_items i WHERE i.run_id = r.id AND i.outcome = 'MISSING_IN_BANK'),
                    amount_mismatch   = (SELECT count(*) FROM recon_items i WHERE i.run_id = r.id AND i.outcome = 'AMOUNT_MISMATCH'),
                    duplicate_in_bank = (SELECT count(*) FROM recon_items i WHERE i.run_id = r.id AND i.outcome = 'DUPLICATE_IN_BANK')
                WHERE r.id = ?
                """, runId);
        return RepeatStatus.FINISHED;
    }
}
