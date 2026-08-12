package com.cakedelight.catalog.dto;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/**
 * One page of results plus the applied paging metadata required by Requirement 1.3.
 *
 * <p>An empty result carries an empty {@code content} list and {@code totalElements} 0
 * (Requirement 1.4).
 *
 * @param content       the records on this page
 * @param page          the applied page number
 * @param size          the applied page size
 * @param totalElements the total record count across all pages
 * @param <T>           the response element type
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements) {

    /** Wraps a Spring Data page, mapping each element with {@code mapper}. */
    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements());
    }
}
