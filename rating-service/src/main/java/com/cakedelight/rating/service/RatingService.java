package com.cakedelight.rating.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cakedelight.rating.domain.Rating;
import com.cakedelight.rating.dto.AverageRatingResponse;
import com.cakedelight.rating.dto.RatingRequest;
import com.cakedelight.rating.dto.RatingResponse;
import com.cakedelight.rating.repository.RatingRepository;
import com.cakedelight.rating.repository.RatingRepository.RatingAggregate;

/**
 * Business logic for ratings: storing a submission and reporting the average per cake.
 *
 * <p>Requirements 7.1, 7.4, 7.5, 7.6.
 */
@Service
public class RatingService {

    /** Average_Rating is rounded to one decimal place (Requirement 7.5). */
    private static final int AVERAGE_SCALE = 1;

    private final RatingRepository ratingRepository;

    public RatingService(RatingRepository ratingRepository) {
        this.ratingRepository = ratingRepository;
    }

    /**
     * Stores one rating for the given cake identifier (Requirement 7.1).
     *
     * <p>The identifier and the submission timestamp are generated here; the client never supplies
     * them. Score bounds are enforced by bean validation on the request and by the
     * {@code CHECK (score BETWEEN 1 AND 5)} constraint in the schema.
     */
    @Transactional
    public RatingResponse submit(UUID cakeId, RatingRequest request) {
        Rating rating = new Rating(
                UUID.randomUUID(),
                cakeId,
                request.customerId(),
                request.score(),
                Instant.now());
        return RatingResponse.from(ratingRepository.save(rating));
    }

    /** All stored ratings for one cake identifier (Requirement 7.4). */
    @Transactional(readOnly = true)
    public List<RatingResponse> findByCakeId(UUID cakeId) {
        return ratingRepository.findByCakeId(cakeId).stream()
                .map(RatingResponse::from)
                .toList();
    }

    /**
     * Average rating and rating count for one cake identifier (Requirements 7.5, 7.6).
     *
     * <p>With no stored rating the average is {@code null} and the count is {@code 0}; that is a
     * successful read, not a not-found (Requirement 7.6).
     *
     * <p>The aggregate average arrives as a {@code Double} per the JPA specification, so it is
     * converted with {@link BigDecimal#valueOf(double)} and never {@code new BigDecimal(double)}.
     * The latter exposes the full binary expansion, where an exact mean of {@code 1.15} reads as
     * {@code 1.1499999...} and rounds down, breaking the half-up contract in Requirement 7.5.
     */
    @Transactional(readOnly = true)
    public AverageRatingResponse average(UUID cakeId) {
        RatingAggregate aggregate = ratingRepository.aggregateByCakeId(cakeId);
        if (aggregate == null || aggregate.getAverageScore() == null) {
            return AverageRatingResponse.empty(cakeId);
        }
        BigDecimal averageRating = BigDecimal.valueOf(aggregate.getAverageScore())
                .setScale(AVERAGE_SCALE, RoundingMode.HALF_UP);
        return new AverageRatingResponse(cakeId, averageRating, aggregate.getRatingCount());
    }
}
