package com.cakedelight.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Cake Delight Catalog Service.
 *
 * <p>Owns the {@code catalog_db} database and serves the cake endpoints under
 * {@code /api/cakes} on port 8081.
 */
@SpringBootApplication
public class CatalogServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }
}
