package com.payledger.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payledger.common.PaymentEventMessage;
import com.payledger.ledger.domain.Account;
import com.payledger.ledger.domain.AccountType;
import com.payledger.ledger.service.LedgerService;
import com.payledger.ledger.service.TransferCommand;
import com.payledger.common.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Real Postgres, real Kafka, the consumer running inside the app. All consumer test classes share this one
 * configuration so Spring reuses a single context (two live contexts would compete in the same consumer group).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ledger.consumer.enabled=true",
        "ledger.consumer.retry.initial=PT0.1S"})
abstract class AbstractConsumerTest extends AbstractKafkaTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper json;
    @Autowired TestRestTemplate http;
    @MockitoSpyBean LedgerService ledger;

    Account settlement;
    Account merchant;

    @BeforeEach
    void freshLedger() {
        jdbc.execute("TRUNCATE postings, journal_entries, account_balances, accounts, processed_events, outbox CASCADE");
        settlement = ledger.createAccount("settlement", AccountType.SETTLEMENT, "INR");
        merchant = ledger.createAccount("merchant-x", AccountType.MERCHANT, "INR");
    }

    Account fundedWallet(long minor) {
        Account wallet = ledger.createAccount("wallet-" + UUID.randomUUID(), AccountType.USER_WALLET, "INR");
        ledger.transfer(new TransferCommand("fund-" + wallet.getId(), settlement.getId(), wallet.getId(),
                Money.of(minor, "INR")));
        return wallet;
    }

    PaymentEventMessage event(String type, UUID paymentId, String merchantId, UUID payer, long minor) {
        return new PaymentEventMessage(UUID.randomUUID(), type, paymentId, merchantId, payer, minor, "INR",
                null, type.substring("payment.".length()).toUpperCase(), Instant.now());
    }

    void publish(PaymentEventMessage event) throws Exception {
        publishRaw(event.paymentId().toString(), json.writeValueAsString(event));
    }

    void publishRaw(String key, String payload) throws Exception {
        kafka.send(PaymentEventMessage.TOPIC, key, payload).get();
    }

    long balance(UUID account) {
        return jdbc.queryForObject("SELECT balance_minor FROM account_balances WHERE account_id = ?", Long.class, account);
    }

    long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    static final Duration WAIT = Duration.ofSeconds(40);
}
