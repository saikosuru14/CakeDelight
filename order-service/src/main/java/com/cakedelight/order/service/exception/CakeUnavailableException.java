package com.cakedelight.order.service.exception;

import java.util.UUID;

/**
 * Thrown when the Catalog Service returns a cake whose availability flag is false.
 *
 * <p>Raised by the catalog client, not by the basket service, so every caller of the client gets the
 * same availability rule without repeating it. Mapped by the global exception handler to HTTP 409
 * with code {@code CAKE_UNAVAILABLE}; the message states that the cake is unavailable
 * (Requirement 3.6).
 */
public class CakeUnavailableException extends RuntimeException {

    private final UUID cakeId;

    public CakeUnavailableException(UUID cakeId) {
        super("Cake " + cakeId + " is unavailable");
        this.cakeId = cakeId;
    }

    public UUID getCakeId() {
        return cakeId;
    }
}
