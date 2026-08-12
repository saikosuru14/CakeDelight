package com.cakedelight.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.cakedelight.order.domain.Order;
import com.cakedelight.order.domain.OrderStatus;

/**
 * Body of the 200 answer to {@code GET /api/orders/{orderId}}, carrying the order identifier,
 * customer identifier, status, total, creation timestamp, and ordered items required by Requirement
 * 5.6.
 *
 * <p>{@code customerEmail} is stored on the order but is not exposed here. Requirement 5.6 does not
 * ask for it, and the address is only needed by the order completed event on its way to the
 * Notification Service, so keeping it out of this unauthenticated read avoids handing a contact
 * address to anyone holding an order identifier.
 *
 * <p>{@code orderTotal} is the committed {@code orders.total} read as-is, not a re-sum of
 * {@code items}, so the value the client sees is the same one checkout stored (Requirements 5.1, 4.6).
 *
 * @param orderId    order identifier
 * @param customerId the customer the order belongs to
 * @param status     order status, {@code CREATED} or {@code CONFIRMED}
 * @param orderTotal committed order total, scale 2
 * @param createdAt  order creation timestamp
 * @param items      one entry per ordered line
 */
public record OrderResponse(
        UUID orderId,
        String customerId,
        OrderStatus status,
        BigDecimal orderTotal,
        Instant createdAt,
        List<OrderItemResponse> items) {

    /**
     * Maps a persisted order onto its response representation.
     *
     * <p>Touches {@code order.getItems()}, so it has to be called with the items already loaded;
     * {@code OrderService.getById} fetches them through the {@code items} entity graph for exactly
     * this reason.
     */
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getCustomerId(),
                order.getStatus(),
                order.getTotal(),
                order.getCreatedAt(),
                order.getItems().stream().map(OrderItemResponse::from).toList());
    }
}
