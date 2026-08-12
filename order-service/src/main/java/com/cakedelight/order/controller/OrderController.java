package com.cakedelight.order.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.cakedelight.order.dto.CheckoutRequest;
import com.cakedelight.order.dto.CheckoutResponse;
import com.cakedelight.order.dto.OrderResponse;
import com.cakedelight.order.dto.OrderStatusResponse;
import com.cakedelight.order.service.CheckoutService;
import com.cakedelight.order.service.OrderService;

import jakarta.validation.Valid;

/**
 * Order endpoints: checkout, the order view, and the status confirmation (Requirements 5.2, 5.5,
 * 5.6, 5.7, 6.5).
 *
 * <p>Holds no business logic and exposes DTOs only. Checkout lives in {@link CheckoutService}, the
 * read and the status transition in {@link OrderService}, and this class is nothing more than the
 * mapping from HTTP onto those calls plus the entity-to-DTO conversion.
 *
 * <p>Nothing is caught here. An empty basket raises {@code EmptyBasketException} and an unknown
 * identifier raises {@code OrderNotFoundException}; the global advice already maps them to 400
 * {@code BASKET_EMPTY} (Requirement 5.4) and 404 {@code ORDER_NOT_FOUND} (Requirement 5.7) in the
 * shared error shape, so a try/catch here would only duplicate that.
 *
 * <p>This controller also publishes nothing. {@code CheckoutService} raises the event payload
 * internally and {@code OrderCompletedPublisher} sends it in the after-commit phase, which is what
 * keeps the event tied to a committed transaction (Requirements 6.1, 6.3) and keeps a broker failure
 * from touching this 201 (Requirement 6.4).
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final CheckoutService checkoutService;
    private final OrderService orderService;

    public OrderController(CheckoutService checkoutService, OrderService orderService) {
        this.checkoutService = checkoutService;
        this.orderService = orderService;
    }

    /**
     * Turns the customer's basket into one order and answers 201 with the order identifier, the order
     * total, and the status (Requirement 5.2).
     *
     * <p>A missing or malformed {@code customerEmail} fails validation on {@link CheckoutRequest} and
     * becomes a 400 naming the field, with no order created (Requirement 5.5). An empty basket is
     * rejected inside {@code checkout} before any write, also with no order created (Requirement 5.4).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CheckoutResponse checkout(@Valid @RequestBody CheckoutRequest request) {
        return CheckoutResponse.from(
                checkoutService.checkout(request.customerId(), request.customerEmail()));
    }

    /**
     * Returns one placed order with its ordered items (Requirement 5.6). An identifier that is not
     * stored raises {@code OrderNotFoundException}, which the advice maps to 404 with the identifier
     * in the message (Requirement 5.7).
     */
    @GetMapping("/{orderId}")
    public OrderResponse getOrder(@PathVariable UUID orderId) {
        return OrderResponse.from(orderService.getById(orderId));
    }

    /**
     * Moves the order to {@code CONFIRMED} and answers 200 with the identifier and the updated status
     * (Requirement 6.5).
     *
     * <p>Modelled as a POST to a {@code confirmation} sub-resource rather than a PATCH on the order,
     * so the client never sends a status value and the only reachable transition stays
     * {@code CREATED -> CONFIRMED}. Confirming is idempotent in {@link OrderService#confirm(UUID)},
     * so repeating the call answers 200 with {@code CONFIRMED} instead of a conflict.
     */
    @PostMapping("/{orderId}/confirmation")
    public OrderStatusResponse confirm(@PathVariable UUID orderId) {
        return OrderStatusResponse.from(orderService.confirm(orderId));
    }
}
