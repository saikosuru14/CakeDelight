package com.cakedelight.order.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.cakedelight.order.domain.Order;
import com.cakedelight.order.domain.OrderStatus;

/**
 * Body of the 201 answer to {@code POST /api/orders}, carrying exactly the three values Requirement
 * 5.2 asks for: the order identifier, the order total, and the order status.
 *
 * <p>Deliberately narrower than {@link OrderResponse}. Checkout confirms that the order was placed
 * and hands back the identifier the client needs for the order view and the confirmation call; the
 * full item list is one {@code GET /api/orders/{orderId}} away, so echoing it here would only
 * duplicate it.
 *
 * <p>{@code orderTotal} is read from the committed order rather than recomputed, so it is the same
 * value as the pre-checkout basket total by construction (Requirements 5.1, 4.6). {@code status} is
 * always {@link OrderStatus#CREATED} at this point, but it is read from the order instead of being
 * hard-coded so the response cannot claim a status the row does not have.
 *
 * @param orderId    identifier of the created order
 * @param orderTotal total of the created order, scale 2
 * @param status     order status, {@code CREATED} for a freshly placed order
 */
public record CheckoutResponse(
        UUID orderId,
        BigDecimal orderTotal,
        OrderStatus status) {

    /** Maps a committed order onto the checkout confirmation. */
    public static CheckoutResponse from(Order order) {
        return new CheckoutResponse(order.getId(), order.getTotal(), order.getStatus());
    }
}
