package com.cakedelight.notification.messaging;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The event this service consumes from {@code order.completed}, carrying the order identifier, the
 * customer identifier, the ordered items, the order total, and the customer contact details the
 * confirmation is sent to (Requirement 8.1).
 *
 * <p>Consumer-side copy of the payload contract. The Order Service keeps its own copy and there is no
 * shared library between the two services, so these field names <em>are</em> the contract and must
 * stay identical to the publisher's: {@code orderId}, {@code customerId}, {@code customerEmail},
 * {@code orderTotal}, {@code createdAt}, {@code items}.
 *
 * <p>The fully qualified name matters as much as the fields. {@code application.yml} declares
 * {@code spring.json.value.default.type: com.cakedelight.notification.messaging.OrderCompletedEvent}
 * and trusts only {@code com.cakedelight.notification.messaging}, because the publisher sends no type
 * headers. Moving this record to another package stops the consumer deserializing anything.
 *
 * <p>{@code orderTotal} is normalized to scale 2 HALF_UP on construction, matching the publisher, so
 * the total that reaches the confirmation has the same scale it was published with.
 */
public record OrderCompletedEvent(
        UUID orderId,
        String customerId,
        String customerEmail,
        BigDecimal orderTotal,
        Instant createdAt,
        List<OrderCompletedItem> items) {

    public OrderCompletedEvent {
        orderTotal = orderTotal == null ? null : orderTotal.setScale(2, RoundingMode.HALF_UP);
        // A payload with no items list is tolerated as an empty one: the listener must not fail on a
        // malformed record, it records the attempt either way.
        items = items == null ? List.of() : List.copyOf(items);
    }
}
