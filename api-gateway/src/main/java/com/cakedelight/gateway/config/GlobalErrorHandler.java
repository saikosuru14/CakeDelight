package com.cakedelight.gateway.config;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import com.cakedelight.gateway.dto.ErrorResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Mono;

/**
 * The single error rendering point for the API Gateway (Requirements 9.3, 9.4, 12.2).
 *
 * <p>The gateway runs on WebFlux, so errors never reach a {@code @RestControllerAdvice}: they surface
 * as a {@link Throwable} handed to an {@link ErrorWebExceptionHandler} together with the
 * {@link ServerWebExchange}. Registered at {@link Order} {@code -1} so it is consulted ahead of
 * Spring's {@code DefaultErrorWebExceptionHandler}.
 *
 * <p>Two mappings matter for the capstone flow:
 *
 * <ul>
 *   <li>a path matching no configured route answers 404 {@code ROUTE_NOT_FOUND} (Requirement 9.3),
 *   <li>a downstream that cannot be reached, either refused or timed out, answers 503
 *       {@code SERVICE_UNAVAILABLE} with a message naming the target service (Requirement 9.4).
 * </ul>
 *
 * <p>No circuit breaker and no retry: an unreachable downstream fails the request straight away.
 */
@Component
@Order(-1)
public class GlobalErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalErrorHandler.class);

    private static final String ROUTE_NOT_FOUND = "ROUTE_NOT_FOUND";
    private static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
    private static final String GATEWAY_ERROR = "GATEWAY_ERROR";

    /** Display name per configured route id, used to name the target service in a 503 message. */
    private static final Map<String, String> SERVICE_NAMES = Map.of(
            "rating-service-ratings", "Rating Service",
            "catalog-service-cakes", "Catalog Service",
            "order-service-baskets-orders", "Order Service",
            "notification-service-notifications", "Notification Service");

    /** Used when a downstream fails before any route was matched. */
    private static final String UNKNOWN_SERVICE = "The target service";

    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper the auto-configured mapper, so {@code timestamp} serializes as ISO-8601
     *                     exactly as it does in the four services
     */
    public GlobalErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable throwable) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            // Bytes already on the wire; nothing left to render.
            return Mono.error(throwable);
        }

        String path = exchange.getRequest().getPath().value();
        String method = exchange.getRequest().getMethod().name();

        HttpStatus status;
        ErrorResponse body;
        if (isUnreachable(throwable)) {
            String service = targetService(exchange);
            log.error("{} unreachable while routing {} {}", service, method, path, throwable);
            status = HttpStatus.SERVICE_UNAVAILABLE;
            body = ErrorResponse.of(
                    SERVICE_UNAVAILABLE,
                    "%s is unavailable, so the request could not be completed".formatted(service),
                    path);
        } else if (isNotFound(throwable)) {
            log.warn("No route configured for {} {}", method, path);
            status = HttpStatus.NOT_FOUND;
            body = ErrorResponse.of(
                    ROUTE_NOT_FOUND, "Unknown route: no service handles " + path, path);
        } else {
            log.error("Unhandled gateway error while routing {} {}", method, path, throwable);
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            body = ErrorResponse.of(
                    GATEWAY_ERROR,
                    "An unexpected error occurred while routing the request",
                    path);
        }

        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory().wrap(serialize(body));
        return response.writeWith(Mono.just(buffer))
                .doOnError(error -> DataBufferUtils.release(buffer));
    }

    /**
     * True when the failure, at any depth of the cause chain, is a refused connection or a timeout.
     * The gateway wraps downstream failures, so the top-level type alone is not enough.
     */
    private static boolean isUnreachable(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof TimeoutException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /**
     * True when the failure is a 404. An unmatched route reaches this handler as a
     * {@link ResponseStatusException} carrying {@link HttpStatus#NOT_FOUND}.
     */
    private static boolean isNotFound(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof ResponseStatusException statusException) {
                HttpStatusCode statusCode = statusException.getStatusCode();
                if (statusCode.equals(HttpStatus.NOT_FOUND)) {
                    return true;
                }
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /**
     * Names the service the request was routed to, read from the matched {@link Route} the gateway
     * stores in the exchange attributes. Falls back to a generic phrasing when no route matched.
     */
    private static String targetService(ServerWebExchange exchange) {
        Object attribute = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (attribute instanceof Route route) {
            return SERVICE_NAMES.getOrDefault(route.getId(), route.getId());
        }
        return UNKNOWN_SERVICE;
    }

    /**
     * Renders the body as JSON. A mapper failure would otherwise replace a useful 503 or 404 with an
     * empty response, so it degrades to a hand-built body in the same shape.
     */
    private byte[] serialize(ErrorResponse body) {
        try {
            return objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException exception) {
            log.error("Failed to serialize gateway error response", exception);
            return """
                    {"code":"%s","message":"%s","timestamp":"%s","path":"%s"}"""
                    .formatted(body.code(), body.message(), body.timestamp(), body.path())
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
}
