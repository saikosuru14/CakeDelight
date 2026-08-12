package com.cakedelight.order.service.exception;

import java.util.UUID;

/**
 * Thrown when the Order Service cannot obtain an answer from the Catalog Service.
 *
 * <p>Covers a connect failure, a read timeout, a 5xx response, and any other response the client
 * cannot interpret. Mapped by the global exception handler to HTTP 503 with code
 * {@code CATALOG_UNAVAILABLE} (Requirements 3.5, 10.2).
 *
 * <p>This is the one retryable catalog failure: {@code CatalogClient} retries it within a bounded
 * budget and only rethrows it once the attempts are exhausted. No fallback price is ever invented.
 */
public class CatalogUnavailableException extends RuntimeException {

    private final UUID cakeId;
    private final String reason;

    public CatalogUnavailableException(UUID cakeId, String reason) {
        this(cakeId, reason, null);
    }

    public CatalogUnavailableException(UUID cakeId, String reason, Throwable cause) {
        super("Catalog Service is unavailable while reading cake " + cakeId + ": " + reason, cause);
        this.cakeId = cakeId;
        this.reason = reason;
    }

    public UUID getCakeId() {
        return cakeId;
    }

    public String getReason() {
        return reason;
    }
}
