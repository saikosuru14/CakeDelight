package com.cakedelight.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Entry point for the Cake Delight Order Service.
 *
 * <p>Owns the {@code order_db} database, serves the basket endpoints under
 * {@code /api/baskets} and the order endpoints under {@code /api/orders} on port 8082,
 * reads cake prices from the Catalog Service, and publishes {@code order.completed}.
 *
 * <p>{@link EnableRetry} activates the {@code @Retryable} interceptor used by
 * {@link com.cakedelight.order.client.CatalogClient} for the transient-failure branch of the
 * outbound catalog read. It is the only retry point in the service.
 */
@SpringBootApplication
@EnableRetry
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
