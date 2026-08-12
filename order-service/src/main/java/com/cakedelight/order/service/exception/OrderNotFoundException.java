package com.cakedelight.order.service.exception;

import java.util.UUID;

/**
 * Thrown when a request targets an order identifier that is not stored (Requirement 5.7).
 *
 * <p>Mapped by the order-service exception advice to HTTP 404 with code {@code ORDER_NOT_FOUND}. The
 * message carries the requested identifier so the error response names it.
 */
public class OrderNotFoundException extends RuntimeException {

    private final UUID orderId;

    public OrderNotFoundException(UUID orderId) {
        super("Order " + orderId + " was not found");
        this.orderId = orderId;
    }

    public UUID getOrderId() {
        return orderId;
    }
}
