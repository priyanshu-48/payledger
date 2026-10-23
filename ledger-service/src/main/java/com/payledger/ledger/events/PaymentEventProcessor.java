package com.payledger.ledger.events;

import com.payledger.common.Money;
import com.payledger.common.PaymentEventMessage;
import com.payledger.ledger.domain.Account;
import com.payledger.ledger.domain.AccountRepository;
import com.payledger.ledger.domain.AccountType;
import com.payledger.ledger.domain.EntryType;
import com.payledger.ledger.service.LedgerService;
import com.payledger.ledger.service.TransferCommand;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one payment event to the ledger exactly once in effect (ADR 0014). Dedup record and journal
 * entry are written in ONE transaction: if posting fails, the dedup row rolls back with it, so the event
 * can be retried; if the offset commit is lost after this commits, the redelivery is skipped.
 */
@Service
public class PaymentEventProcessor {

    public enum Outcome { POSTED, IGNORED, DUPLICATE }

    /** The merchant named in an event has no ledger account yet; not retryable until someone creates it. */
    public static class MerchantNotFoundException extends RuntimeException {
        public MerchantNotFoundException(String merchantId, String currency) {
            super("no MERCHANT account for owner " + merchantId + " in " + currency);
        }
    }

    private final JdbcTemplate jdbc;
    private final AccountRepository accounts;
    private final LedgerService ledger;

    public PaymentEventProcessor(JdbcTemplate jdbc, AccountRepository accounts, LedgerService ledger) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.ledger = ledger;
    }

    @Transactional
    public Outcome process(PaymentEventMessage event) {
        int inserted = jdbc.update("INSERT INTO processed_events (event_id) VALUES (?) ON CONFLICT DO NOTHING",
                event.eventId());
        if (inserted == 0) {
            return Outcome.DUPLICATE; // already handled: the primary key says so
        }
        return switch (event.type()) {
            case PaymentEventMessage.CAPTURED -> post(event, EntryType.PAYMENT_CAPTURE, true);
            case PaymentEventMessage.REFUNDED -> post(event, EntryType.REFUND, false);
            default -> Outcome.IGNORED; // created / authorized / failed ...: nothing moves in the ledger
        };
    }

    /** Capture moves money payer -> merchant; a refund is the same amount merchant -> payer. */
    private Outcome post(PaymentEventMessage event, EntryType type, boolean toMerchant) {
        Account merchant = accounts.findByOwnerIdAndTypeAndCurrency(event.merchantId(), AccountType.MERCHANT,
                        event.currency())
                .orElseThrow(() -> new MerchantNotFoundException(event.merchantId(), event.currency()));
        UUID payer = event.payerAccountId();
        String key = (toMerchant ? "payment-capture:" : "payment-refund:") + event.paymentId();
        TransferCommand command = new TransferCommand(key, toMerchant ? payer : merchant.getId(),
                toMerchant ? merchant.getId() : payer, Money.of(event.amountMinor(), event.currency()), type);
        ledger.transfer(command); // joins this transaction
        return Outcome.POSTED;
    }
}
