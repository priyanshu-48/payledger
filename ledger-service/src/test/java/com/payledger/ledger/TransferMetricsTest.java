package com.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** ledger.transfer.duration is recorded per request with the outcome as a tag (ADR 0020). */
class TransferMetricsTest extends ApiTestSupport {

    @Autowired MeterRegistry registry;

    private long recorded(String outcome) {
        Timer timer = registry.find("ledger.transfer.duration").tag("strategy", "PESSIMISTIC").tag("outcome", outcome).timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    void createdReplayedAndRefusedTransfersLandInTheirOwnOutcome() {
        long created = recorded("created");
        long replayed = recorded("replayed");
        long refused = recorded("InsufficientFundsException");
        UUID settlement = createAccount("SETTLEMENT", "INR");
        UUID alice = fundedWallet(settlement, "100.00");          // 1 created
        UUID bob = createAccount("USER_WALLET", "INR");

        transfer("m-1", alice, bob, "10.00", "INR");               // created
        transfer("m-1", alice, bob, "10.00", "INR");               // replayed
        transfer("m-2", alice, bob, "1000.00", "INR");             // refused: not enough money

        assertThat(recorded("created") - created).isEqualTo(2);
        assertThat(recorded("replayed") - replayed).isEqualTo(1);
        assertThat(recorded("InsufficientFundsException") - refused).isEqualTo(1);
    }

    @Test
    void theTimerPublishesHistogramBucketsSoPrometheusCanComputePercentiles() {
        UUID settlement = createAccount("SETTLEMENT", "INR");
        fundedWallet(settlement, "5.00");

        Timer timer = registry.get("ledger.transfer.duration").tag("outcome", "created").timer();

        assertThat(timer.takeSnapshot().histogramCounts()).as("SLO and percentile buckets").isNotEmpty();
    }
}
