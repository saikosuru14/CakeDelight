package com.cakedelight.catalog.service;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cakedelight.catalog.domain.Cake;
import com.cakedelight.catalog.repository.CakeRepository;
import com.cakedelight.catalog.service.exception.CakeNotFoundException;

/**
 * Business logic for browsing, filtering, and retrieving cakes.
 *
 * <p>Paging defaults and query validation live here rather than in the controller, per the layering
 * rules: controllers hold no business logic and only repositories touch the database.
 */
@Service
@Transactional(readOnly = true)
public class CakeService {

    /** First page index applied when the request omits {@code page} (Requirement 1.2). */
    public static final int DEFAULT_PAGE = 0;

    /** Page size applied when the request omits {@code size} (Requirement 1.2). */
    public static final int DEFAULT_SIZE = 20;

    private final CakeRepository cakeRepository;
    private final CakeQueryValidator queryValidator;

    public CakeService(CakeRepository cakeRepository, CakeQueryValidator queryValidator) {
        this.cakeRepository = cakeRepository;
        this.queryValidator = queryValidator;
    }

    /**
     * Optional list filters. A null field leaves its clause unrestricted, so any combination of
     * filters is ANDed by the repository query (Requirement 2.4).
     */
    public record CakeFilters(String name, String category, BigDecimal minPrice, BigDecimal maxPrice) {

        /** Filters that restrict nothing. */
        public static CakeFilters none() {
            return new CakeFilters(null, null, null, null);
        }
    }

    /**
     * Returns one page of cakes matching every supplied filter. A null or blank filter value is
     * treated as absent. A null {@code page} or {@code size} falls back to page 0 and size 20
     * (Requirement 1.2). No match yields an empty page with a total of 0 (Requirement 1.4).
     *
     * @throws com.cakedelight.catalog.service.exception.InvalidPriceRangeException if
     *         {@code minPrice > maxPrice} (Requirement 2.5)
     */
    public Page<Cake> list(CakeFilters filters, Integer page, Integer size) {
        CakeFilters applied = filters == null ? CakeFilters.none() : filters;
        queryValidator.validatePriceRange(applied.minPrice(), applied.maxPrice());

        Pageable pageable = PageRequest.of(resolvePage(page), resolveSize(size));
        return cakeRepository.search(
                blankToNull(applied.name()),
                blankToNull(applied.category()),
                applied.minPrice(),
                applied.maxPrice(),
                pageable);
    }

    /**
     * Returns the cake with the given identifier.
     *
     * @throws CakeNotFoundException if the identifier is not stored; the message contains the
     *         requested identifier (Requirement 1.6)
     */
    public Cake getById(UUID cakeId) {
        return cakeRepository.findById(cakeId)
                .orElseThrow(() -> new CakeNotFoundException(cakeId));
    }

    private static int resolvePage(Integer page) {
        return page == null || page < 0 ? DEFAULT_PAGE : page;
    }

    private static int resolveSize(Integer size) {
        return size == null || size < 1 ? DEFAULT_SIZE : size;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
