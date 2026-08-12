package com.cakedelight.rating;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Cake Delight Rating Service.
 *
 * <p>Owns the {@code rating_db} database and serves the rating endpoints under
 * {@code /api/cakes/{cakeId}/ratings} on port 8083.
 */
@SpringBootApplication
public class RatingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RatingServiceApplication.class, args);
    }
}
