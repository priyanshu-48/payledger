package com.payledger.ledger.web;

import com.payledger.common.CurrencyMismatchException;
import com.payledger.common.InsufficientFundsException;
import com.payledger.ledger.recon.ReconExceptions.InvalidReconRequestException;
import com.payledger.ledger.recon.ReconExceptions.ReconRunInProgressException;
import com.payledger.ledger.recon.ReconExceptions.ReconRunNotFoundException;
import com.payledger.ledger.service.LedgerExceptions.AccountNotActiveException;
import com.payledger.ledger.service.LedgerExceptions.AccountNotFoundException;
import com.payledger.ledger.service.LedgerExceptions.IdempotencyConflictException;
import com.payledger.ledger.service.LedgerExceptions.InvalidTransferException;
import com.payledger.ledger.service.LedgerExceptions.TransferContentionException;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * One place that turns failures into RFC 9457 problem responses. Never leaks stack traces and never echoes
 * request values (a rejected value could be anything, including something that must not be repeated).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({MissingRequestHeaderException.class, InvalidTransferException.class,
            InvalidReconRequestException.class, MissingServletRequestPartException.class,
            MissingServletRequestParameterException.class})
    ProblemDetail badRequest(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** Names the fields that failed and why, never the values that were sent. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidFields(MethodArgumentNotValidException e) {
        String fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).sorted().collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "invalid request: " + fields);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail wrongType(MethodArgumentTypeMismatchException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid value for parameter '" + e.getName() + "'");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "request body is missing or not valid");
    }

    /** Ownership and role failures raised by the service layer (the filter chain handles URL-level ones). */
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(AccessDeniedException e) {
        return problem(HttpStatus.FORBIDDEN, "you are not allowed to do this");
    }

    @ExceptionHandler({AccountNotFoundException.class, ReconRunNotFoundException.class})
    ProblemDetail notFound(Exception e) {
        return problem(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({InsufficientFundsException.class, CurrencyMismatchException.class,
            AccountNotActiveException.class, IdempotencyConflictException.class})
    ProblemDetail unprocessable(Exception e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler({DataIntegrityViolationException.class, TransferContentionException.class,
            ReconRunInProgressException.class})
    ProblemDetail conflict(Exception e) {
        return problem(HttpStatus.CONFLICT, "request conflicts with current ledger state; retry");
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        return ProblemDetail.forStatusAndDetail(status, detail);
    }
}
