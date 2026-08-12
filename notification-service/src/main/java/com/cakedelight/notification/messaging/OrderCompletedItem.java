package com.cakedelight.notification.messaging;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * One ordered line inside an {@link OrderCompletedEvent}, and the source of the item lines in the
 * confirmation this service sends (Requirement 8.1).
 *
 * <p>Consumer-side copy of the payload contract. The Order Service keeps its own copy of this record
 * and there is no shared library between the two services, so the field names below <em>are</em> the
 * contract and must stay identical to the publisher's: {@code cakeId}, {@code cakeName},
 * {@code unitPrice}, {@code quantity}. Renaming any of them silently breaks JSON deserialization.
 *
 * <p>{@code unitPrice} is normalized to scale 2 HALF_UP in the compact constructor, matching the
 * publisher. Records derive {@code equals} from their components and {@link BigDecimal#equals(Object)}
 * is scale sensitive, so normalizing on both sides is what makes a serialize / deserialize round trip
 * compare equal.
 */
public record OrderCompletedItem(
        UUID cakeId,
        String cakeName,
        BigDecimal unitPrice,
        int quantity) {

    public OrderCompletedItem {
        unitPrice = unitPrice == null ? null : unitPrice.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * The money this line contributes to the order, {@code unitPrice * quantity} at scale 2 HALF_UP.
     * Used only to render the confirmation body; the authoritative order total travels in the event.
     */
    public BigDecimal lineTotal() {
        return unitPrice == null
                ? null
                : unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
    }
}
