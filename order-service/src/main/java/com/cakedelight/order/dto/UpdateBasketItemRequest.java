package com.cakedelight.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body of {@code PUT /api/baskets/{customerId}/items/{cakeId}} (Requirement 4.3).
 *
 * <p>Both the customer and the cake identifier come from the path, so the quantity is the only field
 * a client sends. It is boxed and validated exactly as in {@link AddBasketItemRequest}: an omitted
 * value fails {@code @NotNull} with a 400 naming the field rather than defaulting to 0.
 *
 * @param quantity the quantity that replaces the stored one, must be a positive integer
 */
public record UpdateBasketItemRequest(
        @NotNull(message = "quantity is required")
        @Positive(message = "quantity must be a positive integer")
        Integer quantity) {
}
