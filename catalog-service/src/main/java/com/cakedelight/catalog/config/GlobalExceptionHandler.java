package com.cakedelight.catalog.config;

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

import com.cakedelight.catalog.dto.ErrorResponse;
import com.cakedelight.catalog.service.exception.CakeNotFoundException;
import com.cakedelight.catalog.service.exception.InvalidPriceRangeException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;

/**
 * The single error rendering point for the Catalog Service (Requirements 1.6, 2.5, 2.6, 12.1, 12.2,
 * 12.3).
 *
 * <p>Every handler answers with the shared {@link ErrorResponse} shape. Validation failures name the
 * offending field or request parameter so a caller can correct the request without guessing; the
 * fallback handler logs the stack trace at ERROR and answers with a generic message so no internal
 * detail reaches the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    private static final String INVALID_PRICE_RANGE = "INVALID_PRICE_RANGE";
    private static final String CAKE_NOT_FOUND = "CAKE_NOT_FOUND";
    private static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    /**
     * Request body validation failures (Requirements 12.1, 12.2).
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
     * Request parameter constraint failures raised by the {@code @Validated} controller, for example
     * a negative {@code minPrice} or {@code maxPrice} rejected by {@code @PositiveOrZero}
     * (Requirements 2.6, 12.1).
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleParameterValidation(
            ConstraintViolationException exception, HttpServletRequest request) {
        String message = exception.getConstraintViolations().stream()
                .map(GlobalExceptionHandler::describe)
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "Request validation failed";
        }
        return badRequest(message, request);
    }

    /**
     * A request parameter or path variable that cannot be converted to the declared type, for
     * example a non-numeric {@code minPrice} or a malformed UUID in {@code /api/cakes/{cakeId}}
     * (Requirements 2.6, 12.1).
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
     * A minimum price greater than the supplied maximum price (Requirement 2.5).
     *
     * <p>The exception message already names both price parameters and their values, so it is passed
     * through unchanged.
     */
    @ExceptionHandler(InvalidPriceRangeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPriceRange(
            InvalidPriceRangeException exception, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(
                        INVALID_PRICE_RANGE, exception.getMessage(), request.getRequestURI()));
    }

    /**
     * A cake identifier that is not stored (Requirement 1.6).
     *
     * <p>The exception message already carries the requested identifier, so it is passed through
     * unchanged.
     */
    @ExceptionHandler(CakeNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCakeNotFound(
            CakeNotFoundException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(
                        CAKE_NOT_FOUND, exception.getMessage(), request.getRequestURI()));
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
        return describe(error.getField(), error.getDefaultMessage());
    }

    /**
     * Renders one parameter constraint violation. The property path is
     * {@code <method>.<parameter>}, so only its leaf node names the offending request parameter.
     */
    private static String describe(ConstraintViolation<?> violation) {
        String parameter = leafNode(violation.getPropertyPath());
        return describe(parameter, violation.getMessage());
    }

    private static String describe(String field, String message) {
        if (message == null || message.isBlank()) {
            return field + " is invalid";
        }
        return message.contains(field) ? message : field + " " + message;
    }

    private static String leafNode(Path propertyPath) {
        String leaf = "";
        for (Path.Node node : propertyPath) {
            leaf = node.getName();
        }
        return leaf;
    }
}
