package com.cakedelight.rating.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Average rating and rating count for one cake identifier (Requirements 7.5, 7.6).
 *
 * <p>{@code averageRating} is {@code null} with {@code ratingCount == 0} when the cake identifier
 * has no stored rating; that case is a 200, not a 404 (Requirement 7.6).
 */
public record AverageRatingResponse(UUID cakeId, BigDecimal averageRating, long ratingCount) {

    /** The response for a cake identifier with no stored rating. */
    public static AverageRatingResponse empty(UUID cakeId) {
        return new AverageRatingResponse(cakeId, null, 0L);
    }
}
