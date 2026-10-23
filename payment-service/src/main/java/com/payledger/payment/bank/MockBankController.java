package com.payledger.payment.bank;

import com.payledger.payment.bank.BankDtos.AuthResponse;
import com.payledger.payment.bank.BankDtos.AuthorizeRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP face of the stub bank. Unauthenticated on purpose; locked down in M7. */
@RestController
@RequestMapping("/mock-bank")
@ConditionalOnProperty(name = "payledger.mock-bank.enabled", havingValue = "true", matchIfMissing = true)
class MockBankController {

    private final MockBank bank;

    MockBankController(MockBank bank) {
        this.bank = bank;
    }

    @PostMapping("/authorizations")
    ResponseEntity<AuthResponse> authorize(@RequestBody AuthorizeRequest request) throws InterruptedException {
        try {
            return ResponseEntity.ok(bank.authorize(request));
        } catch (MockBank.StalledException e) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build();
        }
    }

    @GetMapping("/authorizations/{reference}")
    ResponseEntity<AuthResponse> lookup(@PathVariable String reference) {
        return bank.lookup(reference).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/authorizations/{reference}/capture")
    ResponseEntity<AuthResponse> capture(@PathVariable String reference) {
        return bank.settle(reference, "CAPTURED").map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/authorizations/{reference}/refund")
    ResponseEntity<AuthResponse> refund(@PathVariable String reference) {
        return bank.settle(reference, "REFUNDED").map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }
}
