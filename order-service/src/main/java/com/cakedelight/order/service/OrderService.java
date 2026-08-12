package com.cakedelight.order.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cakedelight.order.domain.Order;
import com.cakedelight.order.domain.OrderStatus;
import com.cakedelight.order.repository.OrderRepository;
import com.cakedelight.order.service.exception.OrderNotFoundException;

/**
 * Reads placed orders and advances their status (Requirements 5.6, 5.7, 6.5).
 *
 * <p>Checkout itself lives in {@link CheckoutService}; this service only serves an order back and
 * performs the single allowed status transition {@code CREATED -> CONFIRMED}.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * One order with its line items (Requirement 5.6).
     *
     * <p>Loaded through the {@code items} entity graph so the identifier, customer identifier,
     * status, total, creation timestamp, and every ordered item come back in a single query and no
     * lazy collection is touched after the transaction closes.
     *
     * @throws OrderNotFoundException if the identifier is not stored; the advice maps it to HTTP 404
     *                                {@code ORDER_NOT_FOUND} with the identifier in the message
     *                                (Requirement 5.7)
     */
    @Transactional(readOnly = true)
    public Order getById(UUID orderId) {
        return orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    /**
     * Moves an order to {@link OrderStatus#CONFIRMED} (Requirement 6.5).
     *
     * <p>{@code CREATED -> CONFIRMED} is the only transition in the model, and confirming is
     * idempotent: an order that is already {@code CONFIRMED} is returned unchanged rather than
     * rejected, which is why the endpoint has no conflict status in the design. The only failure is
     * an unknown identifier.
     *
     * @throws OrderNotFoundException if the identifier is not stored (Requirement 5.7)
     */
    @Transactional
    public Order confirm(UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getStatus() == OrderStatus.CONFIRMED) {
            log.info("Order orderId={} is already CONFIRMED, leaving it unchanged", orderId);
            return order;
        }

        order.setStatus(OrderStatus.CONFIRMED);
        Order confirmed = orderRepository.save(order);
        log.info("Order orderId={} moved from CREATED to CONFIRMED", orderId);
        return confirmed;
    }
}
