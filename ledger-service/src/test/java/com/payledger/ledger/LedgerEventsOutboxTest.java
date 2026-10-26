package com.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** ADR 0015: each posted journal entry queues exactly one ledger.entry.posted event, in the same transaction. */
class LedgerEventsOutboxTest extends ApiTestSupport {

    @Autowired ObjectMapper json;

    private JsonNode lastEvent() throws Exception {
        return json.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox ORDER BY seq DESC LIMIT 1", String.class));
    }

    private String owner(UUID account) {
        return jdbc.queryForObject("SELECT owner_id FROM accounts WHERE id = ?", String.class, account);
    }

    @Test
    void everyPostedEntryQueuesExactlyOneEventWithOwnersAndAmount() throws Exception {
        UUID settlement = createAccount("SETTLEMENT", "INR");
        UUID alice = fundedWallet(settlement, "500.00");
        UUID bob = createAccount("USER_WALLET", "INR");
        assertThat(count("outbox")).as("funding transfer").isEqualTo(1);

        transfer("ev-1", alice, bob, "200.25", "INR");

        assertThat(count("outbox")).isEqualTo(2);
        assertThat(count("journal_entries")).isEqualTo(2);
        JsonNode event = lastEvent();
        assertThat(event.get("type").asText()).isEqualTo("ledger.entry.posted");
        assertThat(event.get("entryType").asText()).isEqualTo("TRANSFER");
        assertThat(event.get("externalRef").asText()).isEqualTo("ev-1");
        assertThat(event.get("fromAccountId").asText()).isEqualTo(alice.toString());
        assertThat(event.get("toAccountId").asText()).isEqualTo(bob.toString());
        assertThat(event.get("fromOwner").asText()).isEqualTo(owner(alice));
        assertThat(event.get("toOwner").asText()).isEqualTo(owner(bob));
        assertThat(event.get("amountMinor").asLong()).isEqualTo(20_025);
        assertThat(event.get("currency").asText()).isEqualTo("INR");
        assertThat(jdbc.queryForObject("SELECT aggregate_id::text FROM outbox ORDER BY seq DESC LIMIT 1", String.class))
                .isEqualTo(event.get("entryId").asText());
    }

    @Test
    void refusedAndReplayedTransfersQueueNothing() {
        UUID settlement = createAccount("SETTLEMENT", "INR");
        UUID alice = fundedWallet(settlement, "100.00");
        UUID bob = createAccount("USER_WALLET", "INR");
        transfer("ev-2", alice, bob, "10.00", "INR");
        long queued = count("outbox");

        transfer("ev-2", alice, bob, "10.00", "INR");        // replay: nothing new posted
        transfer("ev-3", alice, bob, "999.00", "INR");       // insufficient funds
        transfer("ev-4", alice, alice, "1.00", "INR");       // invalid
        transfer("ev-5", alice, bob, "1.00", "USD");         // currency mismatch

        assertThat(count("outbox")).isEqualTo(queued);
    }
}
