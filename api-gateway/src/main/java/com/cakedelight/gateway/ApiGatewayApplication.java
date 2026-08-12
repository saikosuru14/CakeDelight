package com.cakedelight.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Cake Delight API Gateway.
 *
 * <p>The only component exposed to clients. Holds a route table only: no business logic and no
 * database. Forwards {@code /api/**} paths unchanged to the owning service on port 8080.
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
