package com.payledger.ledger.web;

import com.payledger.common.Money;
import com.payledger.ledger.domain.Account;
import com.payledger.ledger.service.LedgerService;
import com.payledger.ledger.web.ApiDtos.AccountResponse;
import com.payledger.ledger.web.ApiDtos.BalanceResponse;
import com.payledger.ledger.web.ApiDtos.CreateAccountRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController {

    private final LedgerService ledger;

    public AccountController(LedgerService ledger) {
        this.ledger = ledger;
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@Valid @RequestBody CreateAccountRequest request) {
        Account a = ledger.createAccount(request.ownerId(), request.type(), request.currency());
        return new AccountResponse(a.getId(), a.getOwnerId(), a.getType(), a.getCurrency(), a.getStatus().name());
    }

    @GetMapping("/accounts/{id}/balance")
    public BalanceResponse balance(@PathVariable UUID id) {
        Money m = ledger.getBalance(id);
        return new BalanceResponse(id, m.toMajor(), m.currency());
    }
}
