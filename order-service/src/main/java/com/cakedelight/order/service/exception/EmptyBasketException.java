package com.cakedelight.order.service.exception;

/**
 * Thrown when a checkout is attempted for a customer whose basket holds no basket item
 * (Requirement 5.4).
 *
 * <p>Mapped by the order-service exception advice to HTTP 400 with code {@code BASKET_EMPTY}. The
 * message carries the customer identifier so the error response names it, and it is thrown before
 * any write, so no order is created.
 */
public class EmptyBasketException extends RuntimeException {

    private final String customerId;

    public EmptyBasketException(String customerId) {
        super("The basket of customer " + customerId + " is empty");
        this.customerId = customerId;
    }

    public String getCustomerId() {
        return customerId;
    }
}
