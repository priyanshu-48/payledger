package com.payledger.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Problem responses with fixed or field-level messages: request values (passwords!) are never echoed. */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(UserService.InvalidUserException.class)
    ProblemDetail invalid(UserService.InvalidUserException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "request body is missing or not valid JSON");
    }

    @ExceptionHandler(UserService.UsernameTakenException.class)
    ProblemDetail taken(UserService.UsernameTakenException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
