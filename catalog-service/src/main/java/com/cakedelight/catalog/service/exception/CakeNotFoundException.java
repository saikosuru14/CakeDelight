package com.cakedelight.catalog.service.exception;

import java.util.UUID;

/**
 * Thrown when a cake identifier is not stored in {@code catalog_db}.
 *
 * <p>Mapped by the global exception handler to HTTP 404 with code {@code CAKE_NOT_FOUND}. The
 * message carries the requested identifier so the error response names it (Requirement 1.6).
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
