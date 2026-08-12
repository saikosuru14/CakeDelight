package com.cakedelight.order.service.exception;

import java.util.UUID;

/**
 * Thrown when an update or remove targets a cake identifier that is absent from the customer's
 * basket (Requirement 4.5).
 *
 * <p>Mapped by the global exception handler to HTTP 404 with code {@code BASKET_ITEM_NOT_FOUND}.
 * The message carries the cake identifier so the error response names it, and it is thrown before
 * any write, so the basket is left unchanged.
 */
public class BasketItemNotFoundException extends RuntimeException {

    private final String customerId;
    private final UUID cakeId;

    public BasketItemNotFoundException(String customerId, UUID cakeId) {
        super("Cake " + cakeId + " is not in the basket of customer " + customerId);
        this.customerId = customerId;
        this.cakeId = cakeId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public UUID getCakeId() {
        return cakeId;
    }
}
