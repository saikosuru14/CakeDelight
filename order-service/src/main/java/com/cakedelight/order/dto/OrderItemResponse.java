package com.cakedelight.order.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import com.cakedelight.order.domain.OrderItem;

/**
 * HTTP representation of one ordered line inside an {@link OrderResponse} (Requirement 5.6).
 *
 * <p>This record exists so the {@link OrderItem} JPA entity never crosses the HTTP boundary. The
 * fields mirror {@link BasketItemResponse} so the order view reads like the basket the customer
 * checked out.
 *
 * <p>{@code lineTotal} is derived here rather than stored, because {@code order_items} keeps only the
 * unit price and the quantity. It is a display value only: the authoritative order total is the
 * committed {@code orders.total}, which {@link OrderResponse} reads straight from the entity and
 * never rebuilds by summing these lines. So this multiplication cannot make the reported total drift
 * from the basket total that produced it (Requirements 5.1, 4.6).
 *
 * @param cakeId    cake identifier
 * @param cakeName  cake name as captured at checkout
 * @param unitPrice unit price as captured at checkout, scale 2
 * @param quantity  ordered quantity, always positive
 * @param lineTotal {@code unitPrice * quantity}, scale 2 HALF_UP
 */
public record OrderItemResponse(
        UUID cakeId,
        String cakeName,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineTotal) {

    /** Maps a persisted order line onto its response representation, deriving the line total. */
    public static OrderItemResponse from(OrderItem item) {
        return new OrderItemResponse(
                item.getCakeId(),
                item.getCakeName(),
                item.getUnitPrice(),
                item.getQuantity(),
                item.getUnitPrice()
                        .multiply(BigDecimal.valueOf(item.getQuantity()))
                        .setScale(2, RoundingMode.HALF_UP));
    }
}
