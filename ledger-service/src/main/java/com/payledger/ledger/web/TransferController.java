package com.payledger.ledger.web;

import com.payledger.common.Money;
import com.payledger.ledger.service.LedgerExceptions.InvalidTransferException;
import com.payledger.ledger.service.TransferCommand;
import com.payledger.ledger.service.TransferFacade;
import com.payledger.ledger.service.TransferResult;
import com.payledger.ledger.web.ApiDtos.TransferRequest;
import com.payledger.ledger.web.ApiDtos.TransferResponse;
import com.payledger.security.Caller;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransferController {

    private static final int MAX_KEY_LENGTH = 255;

    private final TransferFacade transfers;

    public TransferController(TransferFacade transfers) {
        this.transfers = transfers;
    }

    /** 201 for a new transfer, 200 when the Idempotency-Key was already processed (same body). */
    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> transfer(@RequestHeader("Idempotency-Key") String key,
                                                     @Valid @RequestBody TransferRequest request,
                                                     Authentication authentication) {
        if (key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new InvalidTransferException("Idempotency-Key must be 1-" + MAX_KEY_LENGTH + " characters");
        }
        Money amount;
        try {
            amount = Money.fromMajor(request.amount(), request.currency());
        } catch (ArithmeticException e) {
            throw new InvalidTransferException("amount must have at most 2 decimal places");
        }
        TransferResult result = transfers.transfer(Caller.from(authentication),
                new TransferCommand(key, request.fromAccountId(), request.toAccountId(), amount));
        TransferResponse body = new TransferResponse(result.entryId(), result.idempotencyKey(), result.from(),
                result.to(), result.amount().toMajor(), result.amount().currency(), result.createdAt());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }
}
