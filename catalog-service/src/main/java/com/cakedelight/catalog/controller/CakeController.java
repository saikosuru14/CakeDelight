package com.cakedelight.catalog.controller;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cakedelight.catalog.domain.Cake;
import com.cakedelight.catalog.dto.CakeResponse;
import com.cakedelight.catalog.dto.PageResponse;
import com.cakedelight.catalog.service.CakeService;

import jakarta.validation.constraints.PositiveOrZero;

/**
 * Read-only HTTP surface for the cake catalog.
 *
 * <p>Holds no business logic: paging defaults, filter handling, and the not-found signal all come
 * from {@link CakeService}. Only DTOs cross this boundary, never the {@link Cake} entity.
 *
 * <p>{@code @Validated} is required for the parameter-level {@code @PositiveOrZero} constraints to
 * be enforced; a violation surfaces as a {@code ConstraintViolationException} that the exception
 * advice maps to 400 naming the offending parameter (Requirement 2.6).
 */
@RestController
@RequestMapping("/api/cakes")
@Validated
public class CakeController {

    private final CakeService cakeService;

    public CakeController(CakeService cakeService) {
        this.cakeService = cakeService;
    }

    /**
     * Lists cakes matching every supplied filter (Requirements 1.1, 2.x).
     *
     * <p>Omitted {@code page} and {@code size} fall back to {@link CakeService#DEFAULT_PAGE} and
     * {@link CakeService#DEFAULT_SIZE} (Requirement 1.2). The response carries the applied page
     * number, page size, and total record count (Requirement 1.3).
     */
    @GetMapping
    public ResponseEntity<PageResponse<CakeResponse>> list(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) @PositiveOrZero BigDecimal minPrice,
            @RequestParam(required = false) @PositiveOrZero BigDecimal maxPrice,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {

        Page<Cake> cakes = cakeService.list(
                new CakeService.CakeFilters(name, category, minPrice, maxPrice), page, size);

        return ResponseEntity.ok(PageResponse.from(cakes, CakeResponse::from));
    }

    /**
     * Returns the full field set of one cake (Requirement 1.5). An unknown identifier raises
     * {@code CakeNotFoundException}, which the exception advice maps to 404 (Requirement 1.6).
     */
    @GetMapping("/{cakeId}")
    public ResponseEntity<CakeResponse> getById(@PathVariable UUID cakeId) {
        return ResponseEntity.ok(CakeResponse.from(cakeService.getById(cakeId)));
    }
}
