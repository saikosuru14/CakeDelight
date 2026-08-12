package com.cakedelight.order.service.exception;

import java.util.UUID;

/**
 * Thrown when the Catalog Service reports that a cake identifier is not stored.
 *
 * <p>Raised by the catalog client on an HTTP 404 from {@code GET /api/cakes/{cakeId}} and mapped by
 * the global exception handler to HTTP 404 with code {@code CAKE_NOT_FOUND}. The message carries the
 * requested identifier so the error response names it (Requirement 3.5).
 */
public class CakeNotFoundException extends RuntimeException {

    private final UUID cakeId;

    public CakeNotFoundException(UUID cakeId) {
        super("Cake " + cakeId + " was not found");
        this.cakeId = cakeId;
    }

    public UUID getCakeId() {
        return cakeId;
    }
}
