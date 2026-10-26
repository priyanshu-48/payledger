package com.payledger.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Writes to the service's own append-only {@code audit_log} table (a trigger rejects UPDATE and DELETE).
 * Called inside the privileged action's transaction when there is one, so the action and its audit row
 * commit together. Never pass secrets or request bodies as details.
 */
public class AuditLog {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AuditLog(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(String actor, String action, String target, Map<String, ?> details) {
        try {
            jdbc.update("INSERT INTO audit_log (actor, action, target, details) VALUES (?, ?, ?, ?::jsonb)",
                    actor, action, target, json.writeValueAsString(details == null ? Map.of() : details));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise audit details for " + action, e);
        }
    }
}
