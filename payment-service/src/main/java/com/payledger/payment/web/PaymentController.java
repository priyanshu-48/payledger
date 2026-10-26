package com.payledger.payment.web;

import com.payledger.common.Money;
import com.payledger.payment.domain.Payment;
import com.payledger.payment.domain.PaymentStatus;
import com.payledger.payment.service.PaymentService;
import com.payledger.payment.service.PaymentService.Created;
import com.payledger.payment.service.PaymentService.CreatePayment;
import com.payledger.payment.web.PaymentDtos.CreatePaymentRequest;
import com.payledger.payment.web.PaymentDtos.EventResponse;
import com.payledger.payment.web.PaymentDtos.PaymentResponse;
import com.payledger.security.Caller;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {

    private static final int MAX_HEADER_LENGTH = 255;

    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    /**
     * 201 for a new payment, 200 when the Idempotency-Key was already processed (same body),
     * 202 while the bank's answer is still unknown; 409 if the first request is in flight, 422 on key reuse.
     * The authenticated merchant is the idempotency scope; its token subject must equal merchantId.
     */
    @PostMapping("/payments")
    public ResponseEntity<PaymentResponse> create(@RequestHeader("Idempotency-Key") String key,
                                                  @Valid @RequestBody CreatePaymentRequest request,
                                                  Authentication authentication) {
        requireHeader("Idempotency-Key", key);
        Money amount;
        try {
            amount = Money.fromMajor(request.amount(), request.currency());
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("amount must have at most 2 decimal places");
        }
        Created created = service.create(Caller.from(authentication),
                new CreatePayment(key, request.merchantId(), request.payerAccountId(), amount));
        Payment p = created.payment();
        HttpStatus status = p.getStatus() == PaymentStatus.AUTHORIZATION_UNKNOWN ? HttpStatus.ACCEPTED
                : created.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).location(URI.create("/payments/" + p.getId()))
                .body(PaymentResponse.of(p));
    }

    @GetMapping("/payments/{id}")
    public PaymentResponse get(@PathVariable UUID id, Authentication authentication) {
        return PaymentResponse.of(service.get(Caller.from(authentication), id));
    }

    @GetMapping("/payments/{id}/events")
    public List<EventResponse> history(@PathVariable UUID id, Authentication authentication) {
        return service.history(Caller.from(authentication), id).stream().map(EventResponse::of).toList();
    }

    @PostMapping("/payments/{id}/capture")
    public PaymentResponse capture(@PathVariable UUID id, Authentication authentication) {
        return PaymentResponse.of(service.capture(Caller.from(authentication), id));
    }

    @PostMapping("/payments/{id}/refund")
    public PaymentResponse refund(@PathVariable UUID id, Authentication authentication) {
        return PaymentResponse.of(service.refund(Caller.from(authentication), id));
    }

    private static void requireHeader(String name, String value) {
        if (value.isBlank() || value.length() > MAX_HEADER_LENGTH) {
            throw new IllegalArgumentException(name + " must be 1-" + MAX_HEADER_LENGTH + " characters");
        }
    }
}
