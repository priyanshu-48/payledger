package com.payledger.ledger.web;

import com.payledger.ledger.events.DltReplayService;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator action: replay dead-lettered payment events after fixing their cause. ADMIN-only once M7 lands. */
@RestController
@ConditionalOnProperty(name = "ledger.consumer.enabled", havingValue = "true", matchIfMissing = true)
class DltAdminController {

    private final DltReplayService replay;

    DltAdminController(DltReplayService replay) {
        this.replay = replay;
    }

    @PostMapping("/admin/dlt/replay")
    Map<String, Integer> replay() throws Exception {
        return Map.of("replayed", replay.replay());
    }
}
