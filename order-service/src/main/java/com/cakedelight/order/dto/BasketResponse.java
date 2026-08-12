package com.cakedelight.order.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * HTTP representation of a customer's basket (Requirement 4.1).
 *
 * <p>A customer with no basket gets an empty {@code items} list and a {@code basketTotal} of
 * {@code 0.00}, not a 404 (Requirement 4.2).
 *
 * <p>Like {@link BasketItemResponse} this record carries no arithmetic: {@code basketTotal} is
 * computed in the single calculation path inside {@code BasketService} (Requirement 4.6).
 *
 * @param customerId  the customer the basket belongs to
 * @param items       one entry per cake identifier in the basket
 * @param basketTotal sum of the line totals, scale 2
 */
public record BasketResponse(
        String customerId,
        List<BasketItemResponse> items,
        BigDecimal basketTotal) {
}
