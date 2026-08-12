package com.cakedelight.order.service;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cakedelight.order.domain.Order;
import com.cakedelight.order.domain.OrderItem;
import com.cakedelight.order.domain.OrderStatus;
import com.cakedelight.order.dto.BasketResponse;
import com.cakedelight.order.messaging.OrderCompletedEvent;
import com.cakedelight.order.messaging.OrderCompletedPublisher;
import com.cakedelight.order.repository.BasketItemRepository;
import com.cakedelight.order.repository.OrderRepository;
import com.cakedelight.order.service.exception.EmptyBasketException;

/**
 * Turns a customer's basket into one placed order (Requirements 5.1, 5.3, 5.4).
 *
 * <h2>One transaction</h2>
 * {@link #checkout(String, String)} is {@code @Transactional}: the order insert, the copied line
 * items, and the basket clear all commit together or all roll back together (Requirement 5.3). No
 * partial checkout is observable, so a failure leaves both the order table and the basket untouched.
 *
 * <h2>Empty basket is rejected before any write</h2>
 * The basket is read first and an empty one throws {@link EmptyBasketException} before the order is
 * built, so no order is created (Requirement 5.4). The advice maps it to HTTP 400 {@code BASKET_EMPTY}.
 *
 * <h2>The total is not recomputed here</h2>
 * The order total is the {@code basketTotal} that {@link BasketService#view(String)} already
 * computed. {@code BasketService} owns the only code that multiplies a unit price by a quantity and
 * sums line totals, so {@code orderTotal == basketTotal} holds by construction rather than by two
 * implementations agreeing (Requirements 5.1, 4.6). Duplicating the arithmetic here is what would
 * let the two drift.
 *
 * <h2>The event leaves only after the commit</h2>
 * {@code checkout} does not send to Kafka. It raises the {@link OrderCompletedEvent} payload as a
 * Spring application event, and {@link OrderCompletedPublisher} receives it in the after-commit
 * phase, so the record reaches {@code order.completed} exactly once per committed checkout
 * (Requirement 6.1) and a rolled back or rejected checkout publishes nothing (Requirement 6.3).
 * Sending inside the transaction would instead emit an event for an order that a later rollback
 * erases. A broker failure is swallowed by the publisher, so the committed checkout still answers 201
 * (Requirement 6.4).
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    private final BasketService basketService;
    private final BasketItemRepository basketItemRepository;
    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher events;

    public CheckoutService(BasketService basketService,
                           BasketItemRepository basketItemRepository,
                           OrderRepository orderRepository,
                           ApplicationEventPublisher events) {
        this.basketService = basketService;
        this.basketItemRepository = basketItemRepository;
        this.orderRepository = orderRepository;
        this.events = events;
    }

    /**
     * Creates one order from the customer's basket and clears that basket in the same transaction
     * (Requirements 5.1, 5.3, 5.4).
     *
     * <p>Every basket item becomes one {@code order_items} row preserving its cake identifier, cake
     * name, unit price, and quantity, so the order reads back as a faithful copy of the basket
     * (Requirements 5.1, 5.6). The order is stored with {@code total = basketTotal} and
     * {@code status = CREATED}.
     *
     * @param customerId    the customer checking out
     * @param customerEmail the contact address carried into the order and later into the event
     * @return the committed order, including its line items, for the caller to render
     * @throws EmptyBasketException if the basket holds no item, thrown before any write so no order
     *                              is created (Requirement 5.4)
     */
    @Transactional
    public Order checkout(String customerId, String customerEmail) {
        // Reuse the single money calculation path instead of recomputing the total here.
        BasketResponse basket = basketService.view(customerId);
        if (basket.items().isEmpty()) {
            throw new EmptyBasketException(customerId);
        }

        Order order = new Order(
                UUID.randomUUID(),
                customerId,
                customerEmail,
                basket.basketTotal(),
                OrderStatus.CREATED,
                Instant.now());
        basket.items().forEach(line -> order.addItem(new OrderItem(
                UUID.randomUUID(),
                line.cakeId(),
                line.cakeName(),
                line.unitPrice(),
                line.quantity())));

        // Items cascade from the order, so this one save inserts the order and all of its lines.
        Order saved = orderRepository.save(order);

        // Same transaction as the insert above: either both are visible or neither is
        // (Requirement 5.3). The repository flushes the pending inserts first, so the delete cannot
        // race the order rows.
        int cleared = basketItemRepository.deleteByCustomerId(customerId);

        log.info("Checkout created order orderId={} customerId={} total={} clearedBasketItems={}",
                saved.getId(), customerId, saved.getTotal(), cleared);

        // Handed to OrderCompletedPublisher only if this transaction commits (Requirements 6.1, 6.3).
        events.publishEvent(OrderCompletedEvent.from(saved));
        return saved;
    }
}
