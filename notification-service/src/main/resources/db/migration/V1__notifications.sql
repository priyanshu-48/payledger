-- The mock mailbox and, at the same time, the dedup record (ADR 0015): one row per (event, recipient).
CREATE TABLE notifications (
    event_id   UUID        NOT NULL,
    recipient  TEXT        NOT NULL,
    subject    TEXT        NOT NULL,
    body       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, recipient)
);
