package com.payledger.ledger.web;

import com.payledger.common.CurrencyMismatchException;
import com.payledger.common.InsufficientFundsException;
import com.payledger.ledger.service.LedgerExceptions.AccountNotActiveException;
import com.payledger.ledger.service.LedgerExceptions.AccountNotFoundException;
import com.payledger.ledger.service.LedgerExceptions.IdempotencyConflictException;
import com.payledger.ledger.service.LedgerExceptions.InvalidTransferException;
import com.payledger.ledger.service.LedgerExceptions.TransferContentionException;
import com.payledger.ledger.recon.ReconExceptions.InvalidReconRequestException;
import com.payledger.ledger.recon.ReconExceptions.ReconRunInProgressException;
import com.payledger.ledger.recon.ReconExceptions.ReconRunNotFoundException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** One place that turns domain failures into RFC 9457 problem responses. Never leaks stack traces. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({MethodArgumentNotValidException.class, MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class, InvalidTransferException.class,
            InvalidReconRequestException.class, MissingServletRequestPartException.class,
            MissingServletRequestParameterException.class})
    ProblemDetail badRequest(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, e.getMessage());
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
