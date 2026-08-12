package com.cakedelight.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.cakedelight.catalog.domain.Cake;
import com.cakedelight.catalog.repository.CakeRepository;
import com.cakedelight.catalog.service.CakeService.CakeFilters;
import com.cakedelight.catalog.service.exception.CakeNotFoundException;

/**
 * Unit tests for {@link CakeService} (spec task 1.7, Requirements 1.2, 1.6, 2.5).
 *
 * <p>Pure Mockito: the repository and the validator are collaborators, so paging defaults, filter
 * normalisation, and the not-found signal are all observable without a Spring context or a database.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CakeService")
class CakeServiceTest {

    @Mock
    private CakeRepository cakeRepository;

    @Mock
    private CakeQueryValidator queryValidator;

    @InjectMocks
    private CakeService cakeService;

    @Captor
    private ArgumentCaptor<Pageable> pageableCaptor;

    private Page<Cake> emptyPage;

    @BeforeEach
    void setUp() {
        emptyPage = new PageImpl<>(List.of());
    }

    @Test
    @DisplayName("applies page 0 and size 20 when page and size are both null")
    void appliesPagingDefaultsWhenAbsent() {
        when(cakeRepository.search(any(), any(), any(), any(), any())).thenReturn(emptyPage);

        cakeService.list(CakeFilters.none(), null, null);

        verify(cakeRepository).search(any(), any(), any(), any(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(CakeService.DEFAULT_PAGE);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(CakeService.DEFAULT_SIZE);
        assertThat(CakeService.DEFAULT_PAGE).isZero();
        assertThat(CakeService.DEFAULT_SIZE).isEqualTo(20);
    }

    /**
     * Out-of-domain paging values fall back to the same defaults rather than reaching
     * {@code PageRequest.of}, which would raise {@code IllegalArgumentException} and surface as a
     * 500 instead of a usable first page.
     */
    @ParameterizedTest(name = "page={0}, size={1} -> page={2}, size={3}")
    @CsvSource({
            "-1,  10,  0, 10",
            "-99, 10,  0, 10",
            "2,   0,   2, 20",
            "2,   -5,  2, 20",
            "-1,  0,   0, 20"
    })
    @DisplayName("falls back to the defaults for a negative page or a size below 1")
    void fallsBackToDefaultsForOutOfDomainPaging(
            int requestedPage, int requestedSize, int expectedPage, int expectedSize) {
        when(cakeRepository.search(any(), any(), any(), any(), any())).thenReturn(emptyPage);

        cakeService.list(CakeFilters.none(), requestedPage, requestedSize);

        verify(cakeRepository).search(any(), any(), any(), any(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(expectedPage);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(expectedSize);
    }

    @Test
    @DisplayName("honours a supplied page and size")
    void honoursSuppliedPaging() {
        when(cakeRepository.search(any(), any(), any(), any(), any())).thenReturn(emptyPage);

        cakeService.list(CakeFilters.none(), 3, 5);

        verify(cakeRepository).search(any(), any(), any(), any(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(3);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    @DisplayName("delegates every supplied filter to the repository unchanged")
    void delegatesFiltersToRepository() {
        Cake cake = cake("Chocolate Truffle", "Birthday", new BigDecimal("23.75"));
        when(cakeRepository.search(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(cake)));

        Page<Cake> result = cakeService.list(
                new CakeFilters("choc", "Birthday", new BigDecimal("10.00"), new BigDecimal("30.00")),
                0, 20);

        verify(cakeRepository).search(
                eq("choc"),
                eq("Birthday"),
                eq(new BigDecimal("10.00")),
                eq(new BigDecimal("30.00")),
                any(Pageable.class));
        assertThat(result.getContent()).containsExactly(cake);
    }

    @Test
    @DisplayName("validates the price range before querying")
    void validatesPriceRange() {
        when(cakeRepository.search(any(), any(), any(), any(), any())).thenReturn(emptyPage);

        cakeService.list(
                new CakeFilters(null, null, new BigDecimal("5.00"), new BigDecimal("9.00")), 0, 20);

        verify(queryValidator).validatePriceRange(new BigDecimal("5.00"), new BigDecimal("9.00"));
    }

    @ParameterizedTest(name = "blank filter value [{0}] reaches the repository as null")
    @ValueSource(strings = {"", " ", "   ", "\t", "\n", " \t \n "})
    @DisplayName("treats a blank name or category as absent")
    void treatsBlankFiltersAsAbsent(String blank) {
        when(cakeRepository.search(any(), any(), any(), any(), any())).thenReturn(emptyPage);

        cakeService.list(new CakeFilters(blank, blank, null, null), 0, 20);

        verify(cakeRepository).search(eq(null), eq(null), eq(null), eq(null), any(Pageable.class));
    }

    @Test
    @DisplayName("trims surrounding whitespace off a non-blank filter")
    void trimsNonBlankFilters() {
        when(cakeRepository.search(any(), any(), any(), any(), any())).thenReturn(emptyPage);

        cakeService.list(new CakeFilters("  velvet  ", "  Birthday  ", null, null), 0, 20);

        verify(cakeRepository)
                .search(eq("velvet"), eq("Birthday"), eq(null), eq(null), any(Pageable.class));
    }

    @Test
    @DisplayName("treats a null filter record as no filters at all")
    void treatsNullFilterRecordAsNoFilters() {
        when(cakeRepository.search(any(), any(), any(), any(), any())).thenReturn(emptyPage);

        cakeService.list(null, null, null);

        verify(cakeRepository).search(eq(null), eq(null), eq(null), eq(null), any(Pageable.class));
    }

    @Test
    @DisplayName("returns an empty page with a total of 0 when nothing matches")
    void returnsEmptyPageWhenNothingMatches() {
        when(cakeRepository.search(any(), any(), any(), any(), any())).thenReturn(emptyPage);

        Page<Cake> result = cakeService.list(new CakeFilters("nothing", null, null, null), 0, 20);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("returns the stored cake for a known identifier")
    void returnsStoredCakeById() {
        Cake cake = cake("Red Velvet Dream", "Birthday", new BigDecimal("27.50"));
        when(cakeRepository.findById(cake.getId())).thenReturn(Optional.of(cake));

        assertThat(cakeService.getById(cake.getId())).isSameAs(cake);
    }

    @Test
    @DisplayName("throws CakeNotFoundException naming the identifier for an unknown cake")
    void throwsNotFoundWithIdentifierInMessage() {
        UUID missingId = UUID.fromString("7f2b1c4e-0000-4000-8000-000000000009");
        when(cakeRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cakeService.getById(missingId))
                .isInstanceOf(CakeNotFoundException.class)
                .hasMessageContaining(missingId.toString())
                .extracting(thrown -> ((CakeNotFoundException) thrown).getCakeId())
                .isEqualTo(missingId);
    }

    private static Cake cake(String name, String category, BigDecimal price) {
        return new Cake(UUID.randomUUID(), name, name + " description", category, price, true, null);
    }
}
