package com.cakedelight.order.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.cakedelight.order.domain.Order;

/**
 * Order persistence. Saving one {@link Order} cascades its items, so checkout writes the whole
 * aggregate in a single transaction (Requirement 5.1).
 */
public interface OrderRepository extends JpaRepository<Order, UUID> {

    /**
     * Order lookup that fetches the item collection in the same query, so the order detail view can
     * be built without relying on an open session ({@code open-in-view} is off). Requirement 5.6.
     */
    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsById(UUID id);
}
