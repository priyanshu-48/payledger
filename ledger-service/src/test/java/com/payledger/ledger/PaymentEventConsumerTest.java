package com.payledger.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import com.payledger.common.PaymentEventMessage;
import com.payledger.ledger.domain.Account;
import com.payledger.ledger.domain.AccountType;
import com.payledger.ledger.events.PaymentEventProcessor;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.support.KafkaHeaders;

class PaymentEventConsumerTest extends AbstractConsumerTest {

    @Autowired PaymentEventProcessor processor;

    private long entries(String externalRef) {
        return count("SELECT count(*) FROM journal_entries WHERE external_ref = ?", externalRef);
    }

    /** Dead letters whose key is the given payment id. */
    private List<ConsumerRecord<String, String>> deadLetters(String key, int expected) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        long deadline = System.nanoTime() + WAIT.toNanos();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(PaymentEventMessage.DEAD_LETTER_TOPIC));
            while (System.nanoTime() < deadline && found.size() < expected) {
                consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (key.equals(r.key())) {
                        found.add(r);
                    }
                });
            }
        }
        return found;
    }

    @Test
    void capturedEventPostsOnePaymentCaptureEntry() throws Exception {
        Account payer = fundedWallet(100_000);
        UUID paymentId = UUID.randomUUID();
        PaymentEventMessage captured = event(PaymentEventMessage.CAPTURED, paymentId, "merchant-x", payer.getId(), 25_000);

        publish(captured);

        await().atMost(WAIT).until(() -> entries("payment-capture:" + paymentId) == 1);
        assertThat(jdbc.queryForObject("SELECT type FROM journal_entries WHERE external_ref = ?", String.class,
                "payment-capture:" + paymentId)).isEqualTo("PAYMENT_CAPTURE");
        assertThat(balance(payer.getId())).isEqualTo(75_000);
        assertThat(balance(merchant.getId())).isEqualTo(25_000);
        assertThat(count("SELECT count(*) FROM processed_events WHERE event_id = ?", captured.eventId())).isEqualTo(1);
    }

    @Test
    void theSameEventDeliveredManyTimesPostsOnce() throws Exception {
        Account payer = fundedWallet(100_000);
        UUID paymentId = UUID.randomUUID();
        PaymentEventMessage captured = event(PaymentEventMessage.CAPTURED, paymentId, "merchant-x", payer.getId(), 10_000);

        for (int i = 0; i < 5; i++) {
            publish(captured);                                  // five Kafka deliveries
        }
        ExecutorService pool = Executors.newFixedThreadPool(20); // and twenty simultaneous direct calls
        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                calls.add(pool.submit(() -> processor.process(captured)));
            }
            for (Future<?> call : calls) {
                call.get();
            }
        } finally {
            pool.shutdownNow();
        }

        await().atMost(WAIT).until(() -> entries("payment-capture:" + paymentId) == 1);
        Thread.sleep(1500); // give any stray duplicate time to show up
        assertThat(entries("payment-capture:" + paymentId)).isEqualTo(1);
        assertThat(balance(payer.getId())).isEqualTo(90_000);
        assertThat(balance(merchant.getId())).isEqualTo(10_000);
        assertThat(count("SELECT count(*) FROM processed_events")).isEqualTo(1);
    }

    @Test
    void refundReversesTheCapture() throws Exception {
        Account payer = fundedWallet(100_000);
        UUID paymentId = UUID.randomUUID();

        publish(event(PaymentEventMessage.CAPTURED, paymentId, "merchant-x", payer.getId(), 30_000));
        publish(event(PaymentEventMessage.REFUNDED, paymentId, "merchant-x", payer.getId(), 30_000));

        await().atMost(WAIT).until(() -> entries("payment-refund:" + paymentId) == 1);
        assertThat(balance(payer.getId())).isEqualTo(100_000);
        assertThat(balance(merchant.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT type FROM journal_entries WHERE external_ref = ?", String.class,
                "payment-refund:" + paymentId)).isEqualTo("REFUND");
    }

    @Test
    void eventsThatDoNotMoveMoneyAreRecordedButNotPosted() throws Exception {
        Account payer = fundedWallet(1_000);
        long entriesBefore = count("SELECT count(*) FROM journal_entries");

        publish(event("payment.created", UUID.randomUUID(), "merchant-x", payer.getId(), 500));
        publish(event("payment.authorized", UUID.randomUUID(), "merchant-x", payer.getId(), 500));

        await().atMost(WAIT).until(() -> count("SELECT count(*) FROM processed_events") == 2);
        assertThat(count("SELECT count(*) FROM journal_entries")).isEqualTo(entriesBefore);
    }

    @Test
    void anUnknownMerchantIsDeadLetteredThenPostedAfterTheFixAndAReplay() throws Exception {
        Account payer = fundedWallet(100_000);
        UUID paymentId = UUID.randomUUID();
        PaymentEventMessage captured = event(PaymentEventMessage.CAPTURED, paymentId, "late-merchant", payer.getId(), 5_000);

        publish(captured);

        List<ConsumerRecord<String, String>> dead = deadLetters(paymentId.toString(), 1);
        assertThat(dead).hasSize(1);
        assertThat(new String(dead.get(0).headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value(),
                StandardCharsets.UTF_8)).isEqualTo(PaymentEventMessage.TOPIC);
        assertThat(count("SELECT count(*) FROM processed_events WHERE event_id = ?", captured.eventId()))
                .as("a failed event leaves no dedup row, so it can be retried").isZero();
        assertThat(entries("payment-capture:" + paymentId)).isZero();

        ledger.createAccount("late-merchant", AccountType.MERCHANT, "INR"); // the operator fixes the cause
        Map<?, ?> result = http.postForObject("/admin/dlt/replay", null, Map.class);

        assertThat(((Number) result.get("replayed")).intValue()).isGreaterThanOrEqualTo(1);
        await().atMost(WAIT).until(() -> entries("payment-capture:" + paymentId) == 1);
        assertThat(balance(payer.getId())).isEqualTo(95_000);
    }

    @Test
    void insufficientFundsGoesStraightToTheDeadLetterTopicWithoutRetriesOrPostings() throws Exception {
        Account payer = fundedWallet(1_000);
        UUID paymentId = UUID.randomUUID();

        publish(event(PaymentEventMessage.CAPTURED, paymentId, "merchant-x", payer.getId(), 5_000));

        assertThat(deadLetters(paymentId.toString(), 1)).hasSize(1);
        assertThat(entries("payment-capture:" + paymentId)).isZero();
        assertThat(balance(payer.getId())).isEqualTo(1_000);
        assertThat(count("SELECT count(*) FROM processed_events")).isZero();
    }

    @Test
    void aTransientDatabaseFailureIsRetriedAndPostedOnce() throws Exception {
        Account payer = fundedWallet(100_000);
        UUID paymentId = UUID.randomUUID();
        doThrow(new TransientDataAccessResourceException("db hiccup 1"))
                .doThrow(new TransientDataAccessResourceException("db hiccup 2"))
                .doCallRealMethod()
                .when(ledger).transfer(any());

        publish(event(PaymentEventMessage.CAPTURED, paymentId, "merchant-x", payer.getId(), 10_000));

        await().atMost(WAIT).until(() -> entries("payment-capture:" + paymentId) == 1);
        assertThat(balance(payer.getId())).isEqualTo(90_000);
        assertThat(count("SELECT count(*) FROM processed_events")).isEqualTo(1);
    }

    @Test
    void anUnreadableMessageIsDeadLetteredAndTheConsumerKeepsGoing() throws Exception {
        Account payer = fundedWallet(100_000);
        String poisonKey = "poison-" + UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        publishRaw(poisonKey, "this is not json");
        publish(event(PaymentEventMessage.CAPTURED, paymentId, "merchant-x", payer.getId(), 1_000));

        assertThat(deadLetters(poisonKey, 1)).hasSize(1);
        await().atMost(WAIT).until(() -> entries("payment-capture:" + paymentId) == 1);
    }
}
