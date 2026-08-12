package com.cakedelight.order.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cakedelight.order.domain.BasketItem;

/**
 * Basket persistence. Backed by {@code idx_basket_items_customer}, which serves both the basket
 * view (Requirement 4.1) and the checkout clear (Requirement 5.3).
 */
public interface BasketItemRepository extends JpaRepository<BasketItem, UUID> {

    /** Every line of one customer's basket; empty when the customer has no basket (Requirement 4.2). */
    List<BasketItem> findByCustomerId(String customerId);

    /**
     * The single line for one cake, unique per customer thanks to {@code uq_basket_customer_cake}.
     * Present means add increments and update replaces; absent means insert or 404 on update and
     * remove (Requirements 3.3, 4.3, 4.4, 4.5).
     */
    Optional<BasketItem> findByCustomerIdAndCakeId(String customerId, UUID cakeId);

    /**
     * Bulk clear of one customer's basket, called inside the checkout transaction after the order
     * items have been copied (Requirement 5.3). {@code flushAutomatically} pushes the pending order
     * inserts to the database before the delete statement runs, so both happen in the same
     * transaction and in the right order. The persistence context is intentionally not cleared, so
     * the caller's already loaded entities stay usable for building the response.
     *
     * @return the number of basket rows removed
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM BasketItem b WHERE b.customerId = :customerId")
    int deleteByCustomerId(@Param("customerId") String customerId);
}
