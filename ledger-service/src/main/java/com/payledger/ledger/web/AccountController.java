package com.payledger.ledger.web;

import com.payledger.common.Money;
import com.payledger.ledger.domain.Account;
import com.payledger.ledger.domain.AccountStatus;
import com.payledger.ledger.service.AccountService;
import com.payledger.ledger.web.ApiDtos.AccountResponse;
import com.payledger.ledger.web.ApiDtos.BalanceResponse;
import com.payledger.ledger.web.ApiDtos.CreateAccountRequest;
import com.payledger.security.Caller;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Thin HTTP layer: who is calling goes to the service, which decides what they may do (ADR 0019). */
@RestController
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@Valid @RequestBody CreateAccountRequest request, Authentication authentication) {
        Account a = accounts.create(Caller.from(authentication), request.ownerId(), request.type(), request.currency());
        return response(a);
    }

    @GetMapping("/accounts/{id}/balance")
    public BalanceResponse balance(@PathVariable UUID id, Authentication authentication) {
        Money m = accounts.balance(Caller.from(authentication), id);
        return new BalanceResponse(id, m.toMajor(), m.currency());
    }

    /** ADMIN only (URL rule and service check). */
    @PostMapping("/accounts/{id}/freeze")
    public AccountResponse freeze(@PathVariable UUID id, Authentication authentication) {
        return response(accounts.setStatus(Caller.from(authentication), id, AccountStatus.FROZEN));
    }

    @PostMapping("/accounts/{id}/unfreeze")
    public AccountResponse unfreeze(@PathVariable UUID id, Authentication authentication) {
        return response(accounts.setStatus(Caller.from(authentication), id, AccountStatus.ACTIVE));
    }

    private static AccountResponse response(Account a) {
        return new AccountResponse(a.getId(), a.getOwnerId(), a.getType(), a.getCurrency(), a.getStatus().name());
    }
}
