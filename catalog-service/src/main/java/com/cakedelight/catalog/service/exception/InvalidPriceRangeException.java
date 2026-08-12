package com.cakedelight.catalog.service.exception;

import java.math.BigDecimal;

/**
 * Thrown when a list request supplies a minimum price greater than the supplied maximum price.
 *
 * <p>Mapped by the global exception handler to HTTP 400 with code {@code INVALID_PRICE_RANGE}. The
 * message names both price parameters and reports their values (Requirement 2.5).
 */
public class InvalidPriceRangeException extends RuntimeException {

    private final BigDecimal minPrice;
    private final BigDecimal maxPrice;

    public InvalidPriceRangeException(BigDecimal minPrice, BigDecimal maxPrice) {
        super("minPrice " + minPrice + " must not be greater than maxPrice " + maxPrice);
        this.minPrice = minPrice;
        this.maxPrice = maxPrice;
    }

    public BigDecimal getMinPrice() {
        return minPrice;
    }

    public BigDecimal getMaxPrice() {
        return maxPrice;
    }
}
