package com.payledger.payment.web;

import com.payledger.payment.bank.BankClient.BankUnavailableException;
import com.payledger.payment.domain.IllegalTransitionException;
import com.payledger.payment.service.PaymentExceptions.IdempotencyKeyReuseException;
import com.payledger.payment.service.PaymentExceptions.PaymentNotFoundException;
import com.payledger.payment.service.PaymentExceptions.RequestInProgressException;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * One place that turns failures into RFC 9457 problem responses. Never leaks stack traces and never echoes
 * request values (a rejected value could be anything, including something that must not be repeated).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler({MissingRequestHeaderException.class, IllegalArgumentException.class})
    ProblemDetail badRequest(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** Names the fields that failed and why, never the values that were sent. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidFields(MethodArgumentNotValidException e) {
        String fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).sorted().collect(Collectors.joining("; "));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "invalid request: " + fields);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail wrongType(MethodArgumentTypeMismatchException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "invalid value for parameter '" + e.getName() + "'");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "request body is missing or not valid");
    }

    /** Ownership and role failures raised by the service layer (the filter chain handles URL-level ones). */
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail forbidden(AccessDeniedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "you are not allowed to do this");
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    ProblemDetail notFound(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalTransitionException.class)
    ProblemDetail illegalTransition(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(RequestInProgressException.class)
    ResponseEntity<ProblemDetail> inProgress(Exception e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).header(HttpHeaders.RETRY_AFTER, "1")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage()));
    }

    @ExceptionHandler(IdempotencyKeyReuseException.class)
    ProblemDetail keyReuse(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(BankUnavailableException.class)
    ProblemDetail bankUnavailable(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
}
