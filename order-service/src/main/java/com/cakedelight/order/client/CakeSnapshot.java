package com.cakedelight.order.client;

import java.math.BigDecimal;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The Order Service's view of a cake owned by the Catalog Service (Requirement 10.2).
 *
 * <p>Deliberately a narrower projection than the catalog's {@code CakeResponse}: only the four
 * fields the basket flow needs. The price becomes the stored unit price of a basket item
 * (Requirement 3.2), the name is echoed back in {@code BasketResponse} (Requirement 4.1), and
 * {@code available} decides between accepting the item and rejecting it (Requirement 3.6).
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} means the Catalog Service can add fields
 * to its response without breaking this client.
 *
 * @param id        cake identifier
 * @param name      cake name
 * @param price     price, scale 2
 * @param available availability flag
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CakeSnapshot(
        UUID id,
        String name,
        BigDecimal price,
        boolean available) {
}
