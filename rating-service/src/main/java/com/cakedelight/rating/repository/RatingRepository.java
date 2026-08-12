package com.cakedelight.rating.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cakedelight.rating.domain.Rating;

public interface RatingRepository extends JpaRepository<Rating, UUID> {

    /** All stored ratings for one cake identifier (Requirement 7.4). */
    List<Rating> findByCakeId(UUID cakeId);

    /**
     * Average score and rating count for one cake identifier in a single result (Requirement 7.5).
     *
     * <p>With no stored ratings the average is {@code null} and the count is {@code 0}
     * (Requirement 7.6).
     */
    @Query("""
            select avg(r.score) as averageScore, count(r) as ratingCount
            from Rating r
            where r.cakeId = :cakeId
            """)
    RatingAggregate aggregateByCakeId(@Param("cakeId") UUID cakeId);

    /**
     * Projection carrying both aggregate values so the average and the count come from one query.
     *
     * <p>{@code avg} is a {@code Double} per the JPA specification. Callers that need a scaled
     * decimal must go through {@link java.math.BigDecimal#valueOf(double)}, never
     * {@code new BigDecimal(double)}, so half-up rounding at one decimal matches the exact
     * arithmetic mean (Requirements 7.5, 7.7).
     */
    interface RatingAggregate {

        /** {@code null} when the cake identifier has no stored rating. */
        Double getAverageScore();

        long getRatingCount();
    }
}
