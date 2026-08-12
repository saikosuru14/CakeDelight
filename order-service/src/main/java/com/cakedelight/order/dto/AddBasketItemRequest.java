package com.cakedelight.order.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body of {@code POST /api/baskets/{customerId}/items} (Requirement 3.1).
 *
 * <p>The customer identifier comes from the path, and the unit price and cake name are captured from
 * the Catalog Service rather than trusted from the client (Requirement 3.2), so neither appears
 * here.
 *
 * <p>{@code quantity} is boxed so that an omitted value fails {@code @NotNull} with a 400 naming the
 * field instead of silently defaulting to 0, and the messages name the parameter because
 * Requirement 3.4 asks for a validation error that identifies it.
 *
 * @param cakeId   identifier of the cake to add
 * @param quantity how many to add, must be a positive integer
 */
public record AddBasketItemRequest(
        @NotNull(message = "cakeId is required") UUID cakeId,
        @NotNull(message = "quantity is required")
        @Positive(message = "quantity must be a positive integer")
        Integer quantity) {
}
