package com.cakedelight.catalog.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.cakedelight.catalog.service.exception.InvalidPriceRangeException;

/**
 * Validates the cake list query parameters that Bean Validation cannot express on its own.
 *
 * <p>Negative and non-numeric price parameters are rejected at the controller boundary
 * ({@code @PositiveOrZero} and type mismatch), so the only cross-parameter rule left is the price
 * range ordering (Requirement 2.5).
 */
@Component
public class CakeQueryValidator {

    /**
     * Rejects a minimum price greater than the maximum price. Either bound may be null, in which
     * case there is no range to compare and the query is left unrestricted on that side.
     *
     * @throws InvalidPriceRangeException if both bounds are present and {@code minPrice > maxPrice}
     */
    public void validatePriceRange(BigDecimal minPrice, BigDecimal maxPrice) {
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new InvalidPriceRangeException(minPrice, maxPrice);
        }
    }
}
