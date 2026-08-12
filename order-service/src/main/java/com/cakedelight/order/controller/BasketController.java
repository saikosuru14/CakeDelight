package com.cakedelight.order.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cakedelight.order.dto.AddBasketItemRequest;
import com.cakedelight.order.dto.BasketResponse;
import com.cakedelight.order.dto.UpdateBasketItemRequest;
import com.cakedelight.order.service.BasketService;

import jakarta.validation.Valid;

/**
 * Basket endpoints for one customer (Requirements 3.1, 3.3, 3.4, 4.1, 4.2, 4.3, 4.4).
 *
 * <p>Holds no business logic and exposes DTOs only. Every decision, including the money arithmetic
 * and the new-item versus increment distinction, lives in {@link BasketService}; the catalog lookup
 * failures and the absent-item signal surface as exceptions the global advice maps to 404, 409, and
 * 503.
 */
@RestController
@RequestMapping("/api/baskets/{customerId}")
public class BasketController {

    private final BasketService basketService;

    public BasketController(BasketService basketService) {
        this.basketService = basketService;
    }

    /**
     * Adds a cake to the customer's basket, or increases the quantity of the line that already holds
     * that cake (Requirements 3.1, 3.3).
     *
     * <p>The status is chosen at request time from {@link BasketService.AddOutcome#created()}: 201
     * when a new basket line was inserted, 200 when an existing line was incremented. That is why
     * this method builds a {@link ResponseEntity} instead of declaring a fixed {@code @ResponseStatus}.
     *
     * <p>A missing or non-positive quantity fails validation on {@link AddBasketItemRequest} and
     * becomes a 400 naming the field, with the basket untouched (Requirement 3.4).
     */
    @PostMapping("/items")
    public ResponseEntity<BasketResponse> addItem(
            @PathVariable String customerId, @Valid @RequestBody AddBasketItemRequest request) {

        BasketService.AddOutcome outcome =
                basketService.add(customerId, request.cakeId(), request.quantity());

        return ResponseEntity
                .status(outcome.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(outcome.basket());
    }

    /**
     * Returns the customer's basket lines and basket total (Requirement 4.1). A customer with no
     * stored items still answers 200, with an empty item list and a total of {@code 0.00}
     * (Requirement 4.2).
     */
    @GetMapping
    public BasketResponse view(@PathVariable String customerId) {
        return basketService.view(customerId);
    }

    /**
     * Replaces the stored quantity of an existing basket line and returns the recalculated basket
     * (Requirement 4.3). A cake identifier that is absent from the basket raises
     * {@code BasketItemNotFoundException}, which the advice maps to 404 (Requirement 4.5).
     */
    @PutMapping("/items/{cakeId}")
    public BasketResponse updateItem(
            @PathVariable String customerId,
            @PathVariable UUID cakeId,
            @Valid @RequestBody UpdateBasketItemRequest request) {
        return basketService.update(customerId, cakeId, request.quantity());
    }

    /**
     * Deletes an existing basket line (Requirement 4.4). The recalculated basket is the response
     * body, so this is a 200 with content rather than a 204.
     */
    @DeleteMapping("/items/{cakeId}")
    public BasketResponse removeItem(@PathVariable String customerId, @PathVariable UUID cakeId) {
        return basketService.remove(customerId, cakeId);
    }
}
