package com.cakedelight.catalog.dto;

import java.math.BigDecimal;
import java.util.UUID;

import com.cakedelight.catalog.domain.Cake;

/**
 * HTTP representation of a cake, carrying the full field set required by Requirement 1.1.
 *
 * <p>This record exists so the {@link Cake} JPA entity never crosses the HTTP boundary.
 *
 * @param id          cake identifier
 * @param name        cake name
 * @param description cake description, may be null
 * @param category    cake category
 * @param price       price, scale 2
 * @param available   availability flag
 * @param imageUrl    image reference, may be null
 */
public record CakeResponse(
        UUID id,
        String name,
        String description,
        String category,
        BigDecimal price,
        boolean available,
        String imageUrl) {

    /** Maps a persisted cake onto its response representation. */
    public static CakeResponse from(Cake cake) {
        return new CakeResponse(
                cake.getId(),
                cake.getName(),
                cake.getDescription(),
                cake.getCategory(),
                cake.getPrice(),
                cake.isAvailable(),
                cake.getImageUrl());
    }
}
