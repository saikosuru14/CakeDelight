package com.cakedelight.catalog.repository;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cakedelight.catalog.domain.Cake;

public interface CakeRepository extends JpaRepository<Cake, UUID> {

    /**
     * Single null-tolerant filter query. Every parameter is optional: a null parameter leaves its
     * clause unrestricted, and supplied filters are ANDed together (Requirement 2.4). Name matches
     * as a case-insensitive substring (2.1), category as case-insensitive equality (2.2), and the
     * price bounds are inclusive (2.3). No match yields an empty page with a total of 0 (1.4).
     */
    @Query("""
            SELECT c FROM Cake c
            WHERE (:name IS NULL OR LOWER(c.name) LIKE LOWER(CONCAT('%', :name, '%')))
              AND (:category IS NULL OR LOWER(c.category) = LOWER(:category))
              AND (:minPrice IS NULL OR c.price >= :minPrice)
              AND (:maxPrice IS NULL OR c.price <= :maxPrice)
            """)
    Page<Cake> search(@Param("name") String name,
                      @Param("category") String category,
                      @Param("minPrice") BigDecimal minPrice,
                      @Param("maxPrice") BigDecimal maxPrice,
                      Pageable pageable);
}
