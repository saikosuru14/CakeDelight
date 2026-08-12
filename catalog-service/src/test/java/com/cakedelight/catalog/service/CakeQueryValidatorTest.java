package com.cakedelight.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.cakedelight.catalog.service.exception.InvalidPriceRangeException;

/**
 * Unit tests for {@link CakeQueryValidator} (spec task 1.7, Requirement 2.5).
 *
 * <p>The only cross-parameter rule in the list query is the price range ordering. Negative and
 * non-numeric price parameters are rejected earlier, at the controller boundary.
 */
@DisplayName("CakeQueryValidator")
class CakeQueryValidatorTest {

    private final CakeQueryValidator validator = new CakeQueryValidator();

    @Test
    @DisplayName("rejects a minimum price greater than the maximum price")
    void rejectsInvertedRange() {
        assertThatThrownBy(() ->
                validator.validatePriceRange(new BigDecimal("30.00"), new BigDecimal("10.00")))
                .isInstanceOf(InvalidPriceRangeException.class);
    }

    @Test
    @DisplayName("names both parameters and both values in the rejection message")
    void rejectionMessageNamesBothParametersAndValues() {
        BigDecimal minPrice = new BigDecimal("30.00");
        BigDecimal maxPrice = new BigDecimal("10.00");

        assertThatThrownBy(() -> validator.validatePriceRange(minPrice, maxPrice))
                .isInstanceOf(InvalidPriceRangeException.class)
                .hasMessageContaining("minPrice")
                .hasMessageContaining("maxPrice")
                .hasMessageContaining("30.00")
                .hasMessageContaining("10.00");
    }

    @Test
    @DisplayName("carries both bounds on the exception for programmatic access")
    void exceptionCarriesBothBounds() {
        BigDecimal minPrice = new BigDecimal("30.00");
        BigDecimal maxPrice = new BigDecimal("10.00");

        assertThatThrownBy(() -> validator.validatePriceRange(minPrice, maxPrice))
                .isInstanceOfSatisfying(InvalidPriceRangeException.class, exception -> {
                    assertThat(exception.getMinPrice()).isEqualByComparingTo(minPrice);
                    assertThat(exception.getMaxPrice()).isEqualByComparingTo(maxPrice);
                });
    }

    @Test
    @DisplayName("accepts equal bounds, because the bounds are inclusive")
    void acceptsEqualBounds() {
        assertThatCode(() ->
                validator.validatePriceRange(new BigDecimal("12.50"), new BigDecimal("12.50")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("accepts equal bounds written at different scales")
    void acceptsEqualBoundsAtDifferentScales() {
        assertThatCode(() -> validator.validatePriceRange(new BigDecimal("12.5"), new BigDecimal("12.50")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("accepts an ordered range")
    void acceptsOrderedRange() {
        assertThatCode(() ->
                validator.validatePriceRange(new BigDecimal("5.00"), new BigDecimal("40.00")))
                .doesNotThrowAnyException();
    }

    /**
     * Either bound may be absent: with only one side supplied there is no range to compare, and the
     * repository query leaves the other side unrestricted.
     */
    @ParameterizedTest(name = "minPrice={0}, maxPrice={1} is accepted")
    @CsvSource(nullValues = "NULL", value = {
            "NULL, 10.00",
            "10.00, NULL",
            "NULL, NULL",
            "NULL, 0.00",
            "0.00, NULL"
    })
    @DisplayName("accepts a null bound on either side")
    void acceptsNullBounds(BigDecimal minPrice, BigDecimal maxPrice) {
        assertThatCode(() -> validator.validatePriceRange(minPrice, maxPrice))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("does not reject a huge maximum against a small minimum")
    void acceptsWideRange() {
        assertThatCode(() ->
                validator.validatePriceRange(BigDecimal.ZERO, new BigDecimal("9999999999.99")))
                .doesNotThrowAnyException();
    }
}
