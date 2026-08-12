package com.cakedelight.order.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.cakedelight.order.domain.BasketItem;

/**
 * HTTP representation of one basket line (Requirement 4.1).
 *
 * <p>This record exists so the {@link BasketItem} JPA entity never crosses the HTTP boundary. It
 * carries no factory method and no arithmetic on purpose: {@code lineTotal} is computed in exactly
 * one place, {@code BasketService}, so the invariant in Requirement 4.6 holds by construction.
 *
 * @param cakeId    cake identifier
 * @param cakeName  cake name snapshot captured when the item was added
 * @param unitPrice unit price snapshot captured when the item was added, scale 2
 * @param quantity  stored quantity, always positive
 * @param lineTotal {@code unitPrice * quantity}, scale 2 HALF_UP
 */
public record BasketItemResponse(
        UUID cakeId,
        String cakeName,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineTotal) {
}
