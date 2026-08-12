package com.cakedelight.order.messaging;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.cakedelight.order.domain.Order;

/**
 * The event published once per committed checkout (Requirement 6.1), carrying the order identifier,
 * the customer identifier, the ordered items, the order total, and the customer contact details
 * (Requirement 6.2).
 *
 * <p>Publisher-side copy of the payload contract in {@code docs/event-contract.md}. The Notification
 * Service keeps its own copy of this record, so these field names are the contract:
 * {@code orderId}, {@code customerId}, {@code customerEmail}, {@code orderTotal}, {@code createdAt},
 * {@code items}.
 *
 * <p>Serialized as JSON by Spring Kafka's {@code JsonSerializer}; {@code orderId} doubles as the
 * message key so all events for one order land on the same partition. {@code orderTotal} is
 * normalized to scale 2 HALF_UP on construction, which is what keeps monetary scale intact across a
 * serialize / deserialize round trip given that records derive {@code equals} from their components.
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
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * Builds the payload from a committed order. Called after the checkout transaction commits, so
     * the line items are already loaded within that transaction.
     */
    public static OrderCompletedEvent from(Order order) {
        return new OrderCompletedEvent(
                order.getId(),
                order.getCustomerId(),
                order.getCustomerEmail(),
                order.getTotal(),
                order.getCreatedAt(),
                order.getItems().stream().map(OrderCompletedItem::from).toList());
    }
}
