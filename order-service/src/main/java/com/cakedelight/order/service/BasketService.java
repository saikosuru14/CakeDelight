package com.cakedelight.order.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import com.cakedelight.order.client.CakeSnapshot;
import com.cakedelight.order.client.CatalogClient;
import com.cakedelight.order.domain.BasketItem;
import com.cakedelight.order.dto.BasketItemResponse;
import com.cakedelight.order.dto.BasketResponse;
import com.cakedelight.order.repository.BasketItemRepository;
import com.cakedelight.order.service.exception.BasketItemNotFoundException;

import jakarta.validation.constraints.Positive;

/**
 * Business logic for baskets: add, view, update, and remove basket items, and the one place where
 * basket money is computed.
 *
 * <p>Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 4.1, 4.2, 4.3, 4.4, 4.5, 4.6.
 *
 * <h2>Single total calculation path</h2>
 * {@link #toResponse(String, List)} and {@link #lineTotal(BasketItem)} are the only code in this
 * service that multiplies or sums money. Every mutating method returns the basket by re-reading the
 * stored rows and running them through that one path, so the reported {@code basketTotal} always
 * equals the sum of the per-line rounded totals of the stored items (Requirement 4.6) without any
 * incremental bookkeeping that could drift.
 *
 * <h2>Nothing is written before the catalog call succeeds</h2>
 * {@link #add} calls {@link CatalogClient#fetchAvailableCake(UUID)} as its first statement. A missing cake,
 * an unavailable cake, or an unreachable catalog throws out of that call, so the basket is untouched
 * on every failure path (Requirements 3.4, 3.5, 3.6).
 *
 * <h2>Quantity validation</h2>
 * Non-positive quantities are rejected twice: by {@code @Positive} on the request DTOs at the web
 * boundary (Requirement 3.4) and by {@code @Positive} on the parameters here, which
 * {@link Validated} turns into a {@code ConstraintViolationException} the global handler maps to
 * HTTP 400. The service-level check keeps the invariant even for a non-HTTP caller and matches the
 * {@code CHECK (quantity > 0)} constraint in the schema.
 */
@Service
@Validated
public class BasketService {

    /** Money is {@code NUMERIC(12,2)}: scale 2, {@link RoundingMode#HALF_UP}. */
    private static final int MONEY_SCALE = 2;

    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    /** Basket total of a customer with no stored items (Requirement 4.2). */
    private static final BigDecimal ZERO_MONEY = BigDecimal.ZERO.setScale(MONEY_SCALE);

    private final BasketItemRepository basketItemRepository;
    private final CatalogClient catalogClient;

    public BasketService(BasketItemRepository basketItemRepository, CatalogClient catalogClient) {
        this.basketItemRepository = basketItemRepository;
        this.catalogClient = catalogClient;
    }

    /**
     * Adds a cake to a customer's basket (Requirements 3.1, 3.2, 3.3).
     *
     * <p>The Catalog Service is read first and its price and name are captured on the new row
     * (Requirement 3.2). When the cake is already in the basket the stored quantity is increased by
     * the requested amount and the captured price is left alone, because a basket line holds the
     * price as it stood when the line was created; {@code uq_basket_customer_cake} is what makes
     * this an increment rather than a duplicate row (Requirement 3.3).
     *
     * @return whether a new line was created, together with the recalculated basket
     */
    @Transactional
    public AddOutcome add(String customerId, UUID cakeId, @Positive int quantity) {
        // Read the catalog before touching the basket, so every failure leaves the basket unchanged
        // (Requirements 3.5, 3.6).
        CakeSnapshot cake = catalogClient.fetchAvailableCake(cakeId);

        Optional<BasketItem> existing = basketItemRepository.findByCustomerIdAndCakeId(customerId, cakeId);
        boolean created = existing.isEmpty();
        if (created) {
            basketItemRepository.save(new BasketItem(
                    UUID.randomUUID(),
                    customerId,
                    cakeId,
                    cake.name(),
                    cake.price(),
                    quantity));
        } else {
            BasketItem item = existing.get();
            item.increaseQuantity(quantity);
            basketItemRepository.save(item);
        }
        return new AddOutcome(created, toResponse(customerId, basketItemRepository.findByCustomerId(customerId)));
    }

    /**
     * The basket of one customer (Requirements 4.1, 4.2).
     *
     * <p>An unknown or empty basket is a successful read of an empty basket with a total of
     * {@code 0.00}, never a not-found (Requirement 4.2).
     */
    @Transactional(readOnly = true)
    public BasketResponse view(String customerId) {
        return toResponse(customerId, basketItemRepository.findByCustomerId(customerId));
    }

    /**
     * Replaces the stored quantity of an existing basket item with the requested positive quantity
     * (Requirement 4.3).
     *
     * @throws BasketItemNotFoundException if the cake identifier is absent from the basket, thrown
     *                                     before any write so the basket is unchanged (Requirement 4.5)
     */
    @Transactional
    public BasketResponse update(String customerId, UUID cakeId, @Positive int quantity) {
        BasketItem item = requireItem(customerId, cakeId);
        item.setQuantity(quantity);
        basketItemRepository.save(item);
        return toResponse(customerId, basketItemRepository.findByCustomerId(customerId));
    }

    /**
     * Deletes an existing basket item (Requirement 4.4).
     *
     * @throws BasketItemNotFoundException if the cake identifier is absent from the basket, thrown
     *                                     before any write so the basket is unchanged (Requirement 4.5)
     */
    @Transactional
    public BasketResponse remove(String customerId, UUID cakeId) {
        basketItemRepository.delete(requireItem(customerId, cakeId));
        return toResponse(customerId, basketItemRepository.findByCustomerId(customerId));
    }

    /** Locates a basket line or fails with the cake identifier in the message (Requirement 4.5). */
    private BasketItem requireItem(String customerId, UUID cakeId) {
        return basketItemRepository.findByCustomerIdAndCakeId(customerId, cakeId)
                .orElseThrow(() -> new BasketItemNotFoundException(customerId, cakeId));
    }

    /**
     * The single money calculation path: one line total per stored item and their sum
     * (Requirement 4.6). No other method in the codebase multiplies a unit price by a quantity or
     * sums line totals for a basket.
     */
    private BasketResponse toResponse(String customerId, List<BasketItem> items) {
        List<BasketItemResponse> lines = items.stream()
                .map(item -> new BasketItemResponse(
                        item.getCakeId(),
                        item.getCakeName(),
                        item.getUnitPrice(),
                        item.getQuantity(),
                        lineTotal(item)))
                .toList();
        BigDecimal basketTotal = lines.stream()
                .map(BasketItemResponse::lineTotal)
                .reduce(ZERO_MONEY, BigDecimal::add);
        return new BasketResponse(customerId, lines, basketTotal);
    }

    /** {@code unitPrice * quantity} rounded to two decimal places HALF_UP. */
    private BigDecimal lineTotal(BasketItem item) {
        return item.getUnitPrice()
                .multiply(BigDecimal.valueOf(item.getQuantity()))
                .setScale(MONEY_SCALE, MONEY_ROUNDING);
    }

    /**
     * Result of an add: {@code created} is true when a new basket line was inserted, which the
     * controller answers with HTTP 201, and false for an increment of an existing line, answered
     * with HTTP 200 (Requirements 3.1, 3.3).
     */
    public record AddOutcome(boolean created, BasketResponse basket) {
    }
}
