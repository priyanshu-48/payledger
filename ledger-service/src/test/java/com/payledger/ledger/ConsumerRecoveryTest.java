package com.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.payledger.common.PaymentEventMessage;
import com.payledger.ledger.domain.Account;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

/**
 * SPEC section 7 "0 lost, 0 duplicated" at JVM level. 300 capture events; the consumer is stopped part-way and
 * restarted, then its committed offsets are rewound to before the first event and everything is read again.
 * The rewind reproduces the nasty crash: the ledger transaction committed but the offset commit never happened.
 */
class ConsumerRecoveryTest extends AbstractConsumerTest {

    private static final int EVENTS = 300;
    private static final long AMOUNT = 100; // 1.00 INR each
    private static final String GROUP = "ledger-service";

    @Autowired KafkaListenerEndpointRegistry registry;

    @Test
    void stopStartAndRewindStillPostEachEventExactlyOnce() throws Exception {
        Account payer = fundedWallet(1_000_000);
        MessageListenerContainer listener = registry.getListenerContainer("payments-events");
        try (Admin admin = Admin.create(Map.<String, Object>of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            awaitCaughtUp(admin);
            Map<TopicPartition, OffsetAndMetadata> startOffsets = latestOffsets(admin);

            List<CompletableFuture<?>> sends = new ArrayList<>();
            for (int i = 0; i < EVENTS; i++) {
                PaymentEventMessage e = event(PaymentEventMessage.CAPTURED, UUID.randomUUID(), "merchant-x",
                        payer.getId(), AMOUNT);
                sends.add(kafka.send(PaymentEventMessage.TOPIC, e.paymentId().toString(), json.writeValueAsString(e)));
            }
            for (CompletableFuture<?> send : sends) {
                send.get();
            }

            await().atMost(WAIT).until(() -> processed() >= 100);
            listener.stop();                                       // consumer goes away part-way through
            long afterStop = processed();
            listener.start();
            await().atMost(WAIT).until(() -> processed() == EVENTS);
            awaitCaughtUp(admin);
            System.out.println("RECOVERY: processed " + afterStop + " before the stop, " + EVENTS + " after the restart");

            listener.stop();                                       // crash after DB commit, before offset commit:
            await().atMost(WAIT).until(() -> groupIsEmpty(admin));
            admin.alterConsumerGroupOffsets(GROUP, startOffsets).all().get(); // ...so Kafka redelivers all 300
            listener.start();
            awaitCaughtUp(admin);
            Thread.sleep(2000);                                    // let any late duplicate show up
        }

        assertThat(count("SELECT count(*) FROM journal_entries WHERE type = 'PAYMENT_CAPTURE'")).as("entries").isEqualTo(EVENTS);
        assertThat(count("SELECT count(DISTINCT external_ref) FROM journal_entries WHERE type = 'PAYMENT_CAPTURE'")).isEqualTo(EVENTS);
        assertThat(count("SELECT count(*) FROM postings p JOIN journal_entries e ON e.id = p.entry_id "
                + "WHERE e.type = 'PAYMENT_CAPTURE'")).as("postings").isEqualTo(2L * EVENTS);
        assertThat(processed()).as("processed_events").isEqualTo(EVENTS);
        assertThat(balance(payer.getId())).isEqualTo(1_000_000 - EVENTS * AMOUNT);
        assertThat(balance(merchant.getId())).isEqualTo(EVENTS * AMOUNT);
        assertThat(count("SELECT COALESCE(sum(balance_minor), 0) FROM account_balances")).as("money conserved").isZero();
    }

    private long processed() {
        return count("SELECT count(*) FROM processed_events");
    }

    private boolean groupIsEmpty(Admin admin) throws Exception {
        ConsumerGroupState state = admin.describeConsumerGroups(List.of(GROUP)).all().get().get(GROUP).state();
        return state == ConsumerGroupState.EMPTY;
    }

    private Map<TopicPartition, OffsetAndMetadata> latestOffsets(Admin admin) throws Exception {
        Map<TopicPartition, OffsetSpec> request = new HashMap<>();
        for (int p = 0; p < 3; p++) {
            request.put(new TopicPartition(PaymentEventMessage.TOPIC, p), OffsetSpec.latest());
        }
        Map<TopicPartition, OffsetAndMetadata> latest = new HashMap<>();
        admin.listOffsets(request).all().get().forEach((tp, info) -> latest.put(tp, new OffsetAndMetadata(info.offset())));
        return latest;
    }

    /** Committed offsets of the ledger group have reached the end of every partition. */
    private void awaitCaughtUp(Admin admin) {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(300)).until(() -> {
            Map<TopicPartition, OffsetAndMetadata> end = latestOffsets(admin);
            Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets(GROUP)
                    .partitionsToOffsetAndMetadata().get();
            return end.entrySet().stream().allMatch(e -> {
                OffsetAndMetadata c = committed.get(e.getKey());
                return e.getValue().offset() == 0 || (c != null && c.offset() >= e.getValue().offset()); // empty partition = nothing owed
            });
        });
    }
}
