package com.payledger.ledger.web;

import com.payledger.ledger.events.DltReplayService;
import com.payledger.security.AuditLog;
import com.payledger.security.Caller;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator action: replay dead-lettered payment events after fixing their cause. ADMIN-only once M7 lands. */
@RestController
@ConditionalOnProperty(name = "ledger.consumer.enabled", havingValue = "true", matchIfMissing = true)
class DltAdminController {

    private final DltReplayService replay;
    private final AuditLog audit;

    DltAdminController(DltReplayService replay, AuditLog audit) {
        this.replay = replay;
        this.audit = audit;
    }

    @PostMapping("/admin/dlt/replay")
    Map<String, Integer> replay(Authentication authentication) throws Exception {
        int replayed = replay.replay();
        audit.record(Caller.from(authentication).subject(), "dlt.replay", "payments.events.DLT", Map.of("replayed", replayed));
        return Map.of("replayed", replayed);
    }
}
