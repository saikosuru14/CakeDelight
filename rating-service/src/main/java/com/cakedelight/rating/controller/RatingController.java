package com.cakedelight.rating.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.cakedelight.rating.dto.AverageRatingResponse;
import com.cakedelight.rating.dto.RatingRequest;
import com.cakedelight.rating.dto.RatingResponse;
import com.cakedelight.rating.service.RatingService;

import jakarta.validation.Valid;

/**
 * Rating endpoints for one cake (Requirements 7.1, 7.4, 7.5, 7.6).
 *
 * <p>Holds no business logic and exposes DTOs only; every decision lives in {@link RatingService}.
 */
@RestController
@RequestMapping("/api/cakes/{cakeId}/ratings")
public class RatingController {

    private final RatingService ratingService;

    public RatingController(RatingService ratingService) {
        this.ratingService = ratingService;
    }

    /** Submits a rating for the cake (Requirement 7.1). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RatingResponse submit(
            @PathVariable UUID cakeId, @Valid @RequestBody RatingRequest request) {
        return ratingService.submit(cakeId, request);
    }

    /** Lists the stored ratings for the cake (Requirement 7.4). */
    @GetMapping
    public List<RatingResponse> list(@PathVariable UUID cakeId) {
        return ratingService.findByCakeId(cakeId);
    }

    /**
     * Reports the average rating and the rating count for the cake (Requirements 7.5, 7.6).
     *
     * <p>A cake with no stored rating still answers 200, with a null average and a count of 0.
     */
    @GetMapping("/average")
    public AverageRatingResponse average(@PathVariable UUID cakeId) {
        return ratingService.average(cakeId);
    }
}
