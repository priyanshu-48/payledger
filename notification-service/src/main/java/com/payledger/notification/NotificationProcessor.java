package com.payledger.notification;

import com.payledger.common.LedgerEventMessage;
import com.payledger.common.Money;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns one ledger event into two mock emails (sender and receiver). The notifications table is the mailbox and
 * the dedup record at once: a row already present for (event, recipient) means this event was handled before,
 * so a redelivery sends nothing new (ADR 0015).
 */
@Service
public class NotificationProcessor {

    private static final Logger log = LoggerFactory.getLogger(NotificationProcessor.class);

    private final JdbcTemplate jdbc;

    public NotificationProcessor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns how many new notifications were stored (0 for a duplicate or an event type we don't mail about). */
    @Transactional
    public int process(LedgerEventMessage event) {
        if (!LedgerEventMessage.ENTRY_POSTED.equals(event.type())) {
            return 0;
        }
        String amount = Money.of(event.amountMinor(), event.currency()).toMajor() + " " + event.currency();
        return deliver(event, event.fromOwner(), "You sent " + amount, "You sent " + amount + " to " + event.toOwner()
                + " (" + event.entryType() + ", ref " + event.externalRef() + ").")
                + deliver(event, event.toOwner(), "You received " + amount, "You received " + amount + " from "
                + event.fromOwner() + " (" + event.entryType() + ", ref " + event.externalRef() + ").");
    }

    private int deliver(LedgerEventMessage event, String recipient, String subject, String body) {
        int inserted = jdbc.update("INSERT INTO notifications (event_id, recipient, subject, body) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT DO NOTHING", event.eventId(), recipient, subject, body);
        if (inserted == 1) {
            log.info("[MOCK EMAIL] to={} subject=\"{}\" event={}", recipient, subject, event.eventId());
        }
        return inserted;
    }
}
