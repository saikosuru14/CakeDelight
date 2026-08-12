package com.cakedelight.order.messaging;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import com.cakedelight.order.domain.OrderItem;

/**
 * One ordered line inside an {@link OrderCompletedEvent} (Requirement 6.2), also the source of the
 * item lines in the confirmation the Notification Service sends (Requirement 8.1).
 *
 * <p>Publisher-side copy of the payload contract in {@code docs/event-contract.md}. The consumer
 * keeps its own copy; there is no shared library between services, so the field names here are the
 * contract: {@code cakeId}, {@code cakeName}, {@code unitPrice}, {@code quantity}.
 *
 * <p>{@code unitPrice} is normalized to scale 2 HALF_UP in the compact constructor. Records derive
 * {@code equals} from their components and {@link BigDecimal#equals(Object)} is scale sensitive, so
 * normalizing on construction is what makes a JSON round trip compare equal.
 */
public record OrderCompletedItem(
        UUID cakeId,
        String cakeName,
        BigDecimal unitPrice,
        int quantity) {

    public OrderCompletedItem {
        unitPrice = unitPrice == null ? null : unitPrice.setScale(2, RoundingMode.HALF_UP);
    }

    /** Copies a persisted order line into its payload form. */
    static OrderCompletedItem from(OrderItem item) {
        return new OrderCompletedItem(
                item.getCakeId(),
                item.getCakeName(),
                item.getUnitPrice(),
                item.getQuantity());
    }
}
