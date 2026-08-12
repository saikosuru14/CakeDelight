package com.cakedelight.rating.dto;

import java.time.Instant;
import java.util.UUID;

import com.cakedelight.rating.domain.Rating;

/**
 * A stored rating as returned by the API (Requirements 7.1, 7.4).
 *
 * <p>Keeps the JPA entity behind the HTTP boundary.
 */
public record RatingResponse(
        UUID id, UUID cakeId, String customerId, int score, Instant createdAt) {

    public static RatingResponse from(Rating rating) {
        return new RatingResponse(
                rating.getId(),
                rating.getCakeId(),
                rating.getCustomerId(),
                rating.getScore(),
                rating.getCreatedAt());
    }
}
