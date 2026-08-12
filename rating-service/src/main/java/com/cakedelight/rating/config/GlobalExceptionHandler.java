package com.cakedelight.rating.config;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.cakedelight.rating.dto.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The single error rendering point for the Rating Service (Requirements 7.2, 7.3, 12.1, 12.2, 12.3).
 *
 * <p>Every handler answers with the shared {@link ErrorResponse} shape. Validation failures name the
 * offending field so a caller can correct the request without guessing; the fallback handler logs the
 * stack trace at ERROR and answers with a generic message so no internal detail reaches the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    private static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    /**
     * Request body validation failures, for example a missing {@code customerId} or a
     * {@code score} outside 1..5 (Requirements 7.2, 7.3, 12.1).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBodyValidation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::describe)
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "Request validation failed";
        }
        return badRequest(message, request);
    }

    /**
     * A path variable or request parameter that cannot be converted to the declared type, for
     * example a malformed UUID in {@code /api/cakes/{cakeId}/ratings} (Requirements 7.3, 12.1).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        Class<?> requiredType = exception.getRequiredType();
        String expectedType = requiredType == null ? "the expected type" : requiredType.getSimpleName();
        String message = "%s has an invalid value '%s'; expected %s"
                .formatted(exception.getName(), exception.getValue(), expectedType);
        return badRequest(message, request);
    }

    /**
     * Anything not handled above (Requirement 12.3).
     *
     * <p>The stack trace goes to the log at ERROR; the response body carries a generic message so
     * internal exception detail never leaks to the caller.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception exception, HttpServletRequest request) {
        log.error("Unhandled exception while processing {} {}",
                request.getMethod(), request.getRequestURI(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(
                        INTERNAL_ERROR,
                        "An unexpected error occurred while processing the request",
                        request.getRequestURI()));
    }

    private static ResponseEntity<ErrorResponse> badRequest(
            String message, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(VALIDATION_ERROR, message, request.getRequestURI()));
    }

    /**
     * Renders one field error so the field name is always present, without repeating it when the
     * configured validation message already names it.
     */
    private static String describe(FieldError error) {
        String field = error.getField();
        String message = error.getDefaultMessage();
        if (message == null || message.isBlank()) {
            return field + " is invalid";
        }
        return message.contains(field) ? message : field + " " + message;
    }
}
