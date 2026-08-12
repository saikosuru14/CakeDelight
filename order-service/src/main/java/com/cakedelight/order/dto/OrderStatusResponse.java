package com.cakedelight.order.dto;

import java.util.UUID;

import com.cakedelight.order.domain.Order;
import com.cakedelight.order.domain.OrderStatus;

/**
 * Body of the 200 answer to {@code POST /api/orders/{orderId}/confirmation}, carrying the order
 * identifier and the updated status Requirement 6.5 asks for.
 *
 * <p>Just those two values: the confirmation call changes nothing else about the order, so echoing
 * the total and the items would repeat what {@code GET /api/orders/{orderId}} already serves.
 *
 * <p>The status is read from the order the service returns, which is what makes the idempotent
 * confirm honest: re-confirming an already {@code CONFIRMED} order answers 200 with
 * {@code CONFIRMED} rather than pretending a transition just happened.
 *
 * @param orderId order identifier
 * @param status  order status after the confirmation attempt, {@code CONFIRMED}
 */
public record OrderStatusResponse(
        UUID orderId,
        OrderStatus status) {

    /** Maps an order onto its status representation. */
    public static OrderStatusResponse from(Order order) {
        return new OrderStatusResponse(order.getId(), order.getStatus());
    }
}
