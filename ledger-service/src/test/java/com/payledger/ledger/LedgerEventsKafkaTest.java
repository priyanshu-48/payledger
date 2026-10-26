package com.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payledger.common.LedgerEventMessage;
import com.payledger.common.Money;
import com.payledger.ledger.domain.Account;
import com.payledger.ledger.domain.AccountType;
import com.payledger.ledger.service.LedgerService;
import com.payledger.ledger.service.TransferCommand;
import com.payledger.outbox.OutboxPoller;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** The ledger's poller delivers queued events to the ledger.events topic. */
@SpringBootTest(properties = {"payledger.outbox.enabled=true", "payledger.outbox.interval=PT1H"})
class LedgerEventsKafkaTest extends AbstractKafkaTest {

    @Autowired LedgerService ledger;
    @Autowired OutboxPoller poller;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @Test
    void aPostedTransferReachesTheLedgerEventsTopic() throws Exception {
        Account settlement = ledger.createAccount("settlement", AccountType.SETTLEMENT, "INR");
        Account wallet = ledger.createAccount("wallet-" + UUID.randomUUID(), AccountType.USER_WALLET, "INR");
        String ref = "kafka-" + UUID.randomUUID();

        ledger.transfer(new TransferCommand(ref, settlement.getId(), wallet.getId(), Money.of(12_345, "INR")));
        while (jdbc.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL", Long.class) > 0) {
            poller.runOnce();
        }

        JsonNode event = awaitEvent(ref);
        assertThat(event.get("type").asText()).isEqualTo(LedgerEventMessage.ENTRY_POSTED);
        assertThat(event.get("amountMinor").asLong()).isEqualTo(12_345);
        assertThat(event.get("toAccountId").asText()).isEqualTo(wallet.getId().toString());
    }

    private JsonNode awaitEvent(String externalRef) throws Exception {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "ledger-events-test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(LedgerEventMessage.TOPIC));
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    JsonNode node = json.readTree(r.value());
                    if (externalRef.equals(node.get("externalRef").asText())) {
                        assertThat(r.key()).isEqualTo(node.get("entryId").asText());
                        return node;
                    }
                }
            }
        }
        throw new AssertionError("no ledger event for " + externalRef + " arrived");
    }
}
