package com.cakedelight.order.config;

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

import com.cakedelight.order.dto.ErrorResponse;
import com.cakedelight.order.service.exception.BasketItemNotFoundException;
import com.cakedelight.order.service.exception.CakeNotFoundException;
import com.cakedelight.order.service.exception.CakeUnavailableException;
import com.cakedelight.order.service.exception.CatalogUnavailableException;
import com.cakedelight.order.service.exception.EmptyBasketException;
import com.cakedelight.order.service.exception.OrderNotFoundException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;

/**
 * The single error rendering point for the Order Service (Requirements 3.4, 3.5, 3.6, 4.5, 5.4, 5.7,
 * 12.1, 12.2, 12.3).
 *
 * <p>Every handler answers with the shared {@link ErrorResponse} shape. Validation failures name the
 * offending field or parameter so a caller can correct the request without guessing; the domain
 * exceptions already carry their identifier in the message, so it is passed through unchanged. The
 * fallback handler logs the stack trace at ERROR and answers with a generic message so no internal
 * detail reaches the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    private static final String BASKET_EMPTY = "BASKET_EMPTY";
    private static final String CAKE_NOT_FOUND = "CAKE_NOT_FOUND";
    private static final String BASKET_ITEM_NOT_FOUND = "BASKET_ITEM_NOT_FOUND";
    private static final String ORDER_NOT_FOUND = "ORDER_NOT_FOUND";
    private static final String CAKE_UNAVAILABLE = "CAKE_UNAVAILABLE";
    private static final String CATALOG_UNAVAILABLE = "CATALOG_UNAVAILABLE";
    private static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    /**
     * Request body validation failures, for example a non-positive {@code quantity} on an add or
     * update request, or a missing or malformed {@code customerEmail} at checkout (Requirements 3.4,
     * 5.5, 12.1, 12.2).
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
     * Parameter constraint failures raised by the {@code @Validated} service layer, for example a
     * non-positive {@code quantity} passed to {@code BasketService.add} or
     * {@code BasketService.update} by a non-HTTP caller (Requirements 3.4, 12.1).
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
     * example a malformed UUID in {@code /api/baskets/{customerId}/items/{cakeId}} or
     * {@code /api/orders/{orderId}} (Requirement 12.1).
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
     * A checkout for a customer whose basket holds no basket item (Requirement 5.4).
     *
     * <p>The exception message already names the customer, so it is passed through unchanged. No
     * order is created.
     */
    @ExceptionHandler(EmptyBasketException.class)
    public ResponseEntity<ErrorResponse> handleEmptyBasket(
            EmptyBasketException exception, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(
                        BASKET_EMPTY, exception.getMessage(), request.getRequestURI()));
    }

    /**
     * A cake identifier the Catalog Service does not know (Requirement 3.5).
     *
     * <p>The exception message already carries the requested identifier, so it is passed through
     * unchanged. The basket is unchanged because nothing is written before the catalog read
     * succeeds.
     */
    @ExceptionHandler(CakeNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCakeNotFound(
            CakeNotFoundException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(
                        CAKE_NOT_FOUND, exception.getMessage(), request.getRequestURI()));
    }

    /**
     * An update or remove targeting a cake identifier that is absent from the basket
     * (Requirement 4.5).
     *
     * <p>The exception message already carries the cake identifier, so it is passed through
     * unchanged. The basket is unchanged because the exception is thrown before any write.
     */
    @ExceptionHandler(BasketItemNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleBasketItemNotFound(
            BasketItemNotFoundException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(
                        BASKET_ITEM_NOT_FOUND, exception.getMessage(), request.getRequestURI()));
    }

    /**
     * An order identifier that is not stored (Requirement 5.7).
     *
     * <p>The exception message already carries the requested identifier, so it is passed through
     * unchanged.
     */
    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleOrderNotFound(
            OrderNotFoundException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(
                        ORDER_NOT_FOUND, exception.getMessage(), request.getRequestURI()));
    }

    /**
     * A cake that exists but whose availability flag is false (Requirement 3.6).
     *
     * <p>The exception message already carries the cake identifier, so it is passed through
     * unchanged. The basket is left unchanged.
     */
    @ExceptionHandler(CakeUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleCakeUnavailable(
            CakeUnavailableException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(
                        CAKE_UNAVAILABLE, exception.getMessage(), request.getRequestURI()));
    }

    /**
     * The Catalog Service could not be reached at all: connection refused, connect timeout, read
     * timeout, or 5xx, on every attempt of the client's bounded retry. There is no fallback price, so
     * the dependency failure surfaces as HTTP 503 and the basket is left unchanged
     * (Requirement 3.6).
     *
     * <p>The exception message already names the cake that could not be read, so it is passed
     * through unchanged.
     */
    @ExceptionHandler(CatalogUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleCatalogUnavailable(
            CatalogUnavailableException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ErrorResponse.of(
                        CATALOG_UNAVAILABLE, exception.getMessage(), request.getRequestURI()));
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
     * {@code <method>.<parameter>}, so only its leaf node names the offending parameter.
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
