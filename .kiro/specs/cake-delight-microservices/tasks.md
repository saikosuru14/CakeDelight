# Implementation Plan: Cake Delight Microservices

## Overview

Five independent Maven projects are scaffolded from an empty workspace, one service at a time, so
each is buildable and testable before the next starts. Catalog Service comes first because the Order
Service reads prices from it; Rating Service is independent and follows; Order Service adds the
basket, checkout, and the `order.completed` publisher; Notification Service consumes that event; the
gateway is wired last, followed by Docker Compose, Kubernetes manifests, and docs.

Stack per `.kiro/steering/tech.md`: Java 21, Spring Boot 3.3.x, Maven (no parent aggregator),
PostgreSQL 16 with Spring Data JPA + Flyway, Apache Kafka 3.7 via Spring for Apache Kafka, Spring
Cloud Gateway, springdoc-openapi, JUnit 5 + Spring Boot Test, Testcontainers, jqwik 1.8.x.

## Tasks

- [ ] 1. Catalog Service (port 8081, `catalog_db`)
  - [x] 1.1 Scaffold the catalog-service Maven project and application configuration
    - Create `catalog-service/pom.xml`: Java 21, `spring-boot-starter-parent` 3.3.x, starters web, validation, data-jpa, actuator; `flyway-core`, `flyway-database-postgresql`, `postgresql`, `springdoc-openapi-starter-webmvc-ui`; test scope `spring-boot-starter-test`, `testcontainers` + `postgresql` + `junit-jupiter`. All versions pinned explicitly
    - Create `catalog-service/src/main/java/com/cakedelight/catalog/CatalogServiceApplication.java`
    - Create `catalog-service/src/main/resources/application.yml`: `server.port: 8081`, datasource URL/username/password from `SPRING_DATASOURCE_*` env vars only, Flyway enabled on `classpath:db/migration`, `management.endpoint.health.group.liveness` and `.readiness` exposed at `/actuator/health`, springdoc at `/swagger-ui.html`
    - _Requirements: 10.1, 12.4_

  - [ ] 1.2 Add the cakes schema and demo seed migrations
    - Create `catalog-service/src/main/resources/db/migration/V1__create_cakes.sql` with the `cakes` table, the `price >= 0` check, and the `lower(category)`, `lower(name)`, `price` indexes exactly as specified in design.md
    - Create `V2__seed_cakes.sql` inserting a small demo catalog spanning at least three categories and a range of prices, including one row with `available = false`
    - _Requirements: 1.1, 2.1, 2.2, 2.3, 10.1_

  - [ ] 1.3 Implement the Cake entity and the filtering repository
    - Create `domain/Cake.java` (UUID id, name, description, category, `BigDecimal` price scale 2, available, imageUrl; no web or messaging annotations)
    - Create `repository/CakeRepository.java` extending `JpaRepository<Cake, UUID>` with a single null-tolerant `@Query` that ANDs case-insensitive name `like`, case-insensitive category equality, and inclusive `minPrice`/`maxPrice` bounds, returning `Page<Cake>`
    - _Requirements: 1.1, 1.4, 2.1, 2.2, 2.3, 2.4_

  - [ ] 1.4 Implement CakeService and CakeQueryValidator
    - Create `service/CakeQueryValidator.java` rejecting `minPrice > maxPrice` with `InvalidPriceRangeException` whose message names both parameters
    - Create `service/CakeService.java` with `list(filters, page, size)` defaulting to page 0 / size 20 and `getById(UUID)` throwing `CakeNotFoundException` containing the identifier
    - Create `service/exception/CakeNotFoundException.java` and `service/exception/InvalidPriceRangeException.java`
    - _Requirements: 1.2, 1.5, 1.6, 2.4, 2.5_

  - [ ] 1.5 Implement the cake DTOs and CakeController
    - Create `dto/CakeResponse.java` and `dto/PageResponse.java` (`content`, `page`, `size`, `totalElements`) as records
    - Create `controller/CakeController.java` with `GET /api/cakes` (params `name`, `category`, `minPrice`, `maxPrice`, `page` default 0, `size` default 20, prices annotated `@PositiveOrZero`) and `GET /api/cakes/{cakeId}`; controller delegates to `CakeService` and never returns entities
    - _Requirements: 1.1, 1.2, 1.3, 1.5, 2.6_

  - [ ] 1.6 Add the shared error shape, exception advice, and request logging filter
    - Create `dto/ErrorResponse.java` record (`code`, `message`, `timestamp`, `path`)
    - Create `config/GlobalExceptionHandler.java` (`@RestControllerAdvice`) mapping validation and type-mismatch exceptions to 400 `VALIDATION_ERROR` naming the offending field, `InvalidPriceRangeException` to 400 `INVALID_PRICE_RANGE`, `CakeNotFoundException` to 404 `CAKE_NOT_FOUND`, and `Exception` to 500 `INTERNAL_ERROR` with the stack trace logged at ERROR
    - Create `config/RequestLoggingFilter.java` generating or reusing `X-Request-Id`, putting it in the MDC, and logging request id, method, path, status, and elapsed milliseconds
    - _Requirements: 1.6, 2.5, 2.6, 12.1, 12.2, 12.3, 12.5_

  - [ ]* 1.7 Write unit tests for the catalog service layer
    - Create `CakeServiceTest` (Mockito): page 0 / size 20 defaults, filter delegation, not-found path
    - Create `CakeQueryValidatorTest`: `minPrice > maxPrice` rejection and message content
    - _Requirements: 1.2, 1.6, 2.5_

  - [ ]* 1.8 Write web layer tests for the catalog controller
    - Create `CakeControllerTest` (`@WebMvcTest`): response field set and page metadata, 200/400/404 statuses, error body shape, negative and non-numeric price parameters
    - Create `RequestLoggingFilterTest` asserting all five logged fields
    - _Requirements: 1.1, 1.3, 1.5, 1.6, 2.6, 12.1, 12.2, 12.5_

  - [ ]* 1.9 Write Testcontainers integration tests for the catalog repository
    - Create `CakeRepositoryIT` (Testcontainers PostgreSQL, Flyway applied): case-insensitive name and category filters, inclusive price bounds, combined filters, empty result with count 0
    - Create `HealthEndpointIT` asserting 200 from `/actuator/health/liveness` and `/actuator/health/readiness`
    - _Requirements: 1.4, 2.1, 2.2, 2.3, 2.4, 12.4_

- [ ] 2. Rating Service (port 8083, `rating_db`)
  - [x] 2.1 Scaffold the rating-service Maven project and application configuration
    - Create `rating-service/pom.xml` mirroring the catalog dependency set and adding `net.jqwik:jqwik:1.8.x` in test scope
    - Create `src/main/java/com/cakedelight/rating/RatingServiceApplication.java` and `src/main/resources/application.yml` with `server.port: 8083`, env-var datasource, Flyway, actuator health groups, springdoc
    - _Requirements: 10.1, 12.4_

  - [ ] 2.2 Add the ratings migration, entity, and repository
    - Create `db/migration/V1__create_ratings.sql` with the `ratings` table, `CHECK (score BETWEEN 1 AND 5)`, and `idx_ratings_cake`
    - Create `domain/Rating.java` and `repository/RatingRepository.java` with `findByCakeId` and an aggregate query returning `avg(score)` and `count(*)` for a cake identifier
    - _Requirements: 7.1, 7.4, 7.5, 7.7, 10.1_

  - [ ] 2.3 Implement RatingService, DTOs, and RatingController
    - Create `service/RatingService.java`: store a rating with generated UUID and timestamp; `average(cakeId)` rounding to one decimal HALF_UP and returning `null` average with count 0 when no ratings exist
    - Create `dto/RatingRequest.java` (`@NotNull customerId`, `@Min(1) @Max(5) score`), `dto/RatingResponse.java`, `dto/AverageRatingResponse.java`
    - Create `controller/RatingController.java` with `POST /api/cakes/{cakeId}/ratings` (201), `GET /api/cakes/{cakeId}/ratings` (200), `GET /api/cakes/{cakeId}/ratings/average` (200)
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6_

  - [ ] 2.4 Add the error shape, exception advice, and request logging filter
    - Create `dto/ErrorResponse.java`, `config/GlobalExceptionHandler.java` (400 `VALIDATION_ERROR` naming the offending field for body validation and path type mismatch, 500 `INTERNAL_ERROR` fallback with ERROR log), `config/RequestLoggingFilter.java`
    - _Requirements: 7.2, 7.3, 12.1, 12.2, 12.3, 12.5_

  - [ ]* 2.5 Write unit and web layer tests for the rating service
    - Create `RatingServiceTest`: store, list by cake, null average with count 0
    - Create `RatingControllerTest` (`@WebMvcTest`): scores 0, 6, and -1 rejected, missing `customerId` rejected, 201 and 200 response shapes, error body shape
    - Create `RequestLoggingFilterTest`
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.6, 12.1, 12.2, 12.5_

  - [ ]* 2.6 Write Testcontainers integration tests for the rating repository
    - Create `RatingRepositoryIT`: `findByCakeId`, aggregate average and count, `CHECK` constraint rejection of out-of-range scores
    - Create `HealthEndpointIT`
    - _Requirements: 7.4, 7.5, 7.7, 12.4_

  - [ ]* 2.7 Write the average rating property test
    - Create `AverageRatingPropertyTest` (jqwik, `@Property(tries = 100)`)
    - **Property 3: Average rating stays within 1.0 and 5.0** — generate a cake identifier and a list of 1..50 scores in [1,5], assert the reported average is within [1.0, 5.0], equals the arithmetic mean rounded to one decimal HALF_UP, and `ratingCount` equals the number of stored ratings
    - **Validates: Requirements 7.5, 7.7**

- [ ] 3. Checkpoint - Catalog and Rating services build and pass
  - Run `mvn clean verify` in `catalog-service` and `rating-service`. Ensure all tests pass, ask the user if questions arise.

- [ ] 4. Order Service basket and catalog client (port 8082, `order_db`)
  - [x] 4.1 Scaffold the order-service Maven project and application configuration
    - Create `order-service/pom.xml` with the catalog dependency set plus `spring-kafka`, and test scope `spring-kafka-test`, `testcontainers` (`postgresql`, `kafka`), `jqwik:1.8.x`
    - Create `src/main/java/com/cakedelight/order/OrderServiceApplication.java`
    - Create `src/main/resources/application.yml`: `server.port: 8082`, env-var datasource, Flyway, `SPRING_KAFKA_BOOTSTRAP_SERVERS` with `JsonSerializer` producer settings, `CATALOG_SERVICE_URL`, actuator health groups, springdoc
    - Create `config/RestClientConfig.java` exposing a `RestClient` bean with 5 s connect and 5 s read timeouts pointed at `CATALOG_SERVICE_URL`
    - _Requirements: 10.1, 10.2, 12.4_

  - [ ] 4.2 Add the basket and order schema migration
    - Create `db/migration/V1__create_baskets_and_orders.sql` with `basket_items` (including `uq_basket_customer_cake` and `idx_basket_items_customer`), `orders`, and `order_items` plus their checks, foreign key, and indexes as specified in design.md
    - _Requirements: 3.3, 4.1, 5.1, 5.3, 10.1_

  - [ ] 4.3 Implement the order domain entities and repositories
    - Create `domain/BasketItem.java`, `domain/Order.java`, `domain/OrderItem.java`, `domain/OrderStatus.java` (`CREATED`, `CONFIRMED`) with `BigDecimal(12,2)` money fields
    - Create `repository/BasketItemRepository.java` (`findByCustomerId`, `findByCustomerIdAndCakeId`, `deleteByCustomerId`) and `repository/OrderRepository.java`
    - _Requirements: 4.1, 5.1, 5.6, 10.1_

  - [ ] 4.4 Implement the catalog client and its failure mapping
    - Create `client/CatalogClient.java` calling `GET /api/cakes/{cakeId}` and `client/CakeSnapshot.java` (`id`, `name`, `price`, `available`)
    - Create `service/exception/CakeNotFoundException.java`, `CakeUnavailableException.java`, `CatalogUnavailableException.java`; map catalog 404 to not-found, `available == false` to unavailable, and connect/read timeout to catalog-unavailable
    - _Requirements: 3.2, 3.5, 3.6, 10.2_

  - [ ] 4.5 Implement BasketService with the single total calculation path
    - Create `service/BasketService.java`: add (insert new item with the catalog price captured, or increment quantity of an existing item), update to a positive quantity, remove, and view; compute `lineTotal = unitPrice * quantity` scaled 2 HALF_UP and `basketTotal = sum(lineTotal)` in one place; throw `BasketItemNotFoundException` for absent cake identifiers and perform no write before the catalog call succeeds
    - Create `service/exception/BasketItemNotFoundException.java`
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 4.1, 4.2, 4.3, 4.4, 4.5, 4.6_

  - [ ] 4.6 Implement the basket DTOs and BasketController
    - Create `dto/AddBasketItemRequest.java` (`@NotNull cakeId`, `@NotNull @Positive quantity`), `dto/UpdateBasketItemRequest.java`, `dto/BasketItemResponse.java`, `dto/BasketResponse.java` (`customerId`, `items`, `basketTotal`)
    - Create `controller/BasketController.java`: `POST /api/baskets/{customerId}/items` returning 201 for a new item and 200 for an increment, `GET /api/baskets/{customerId}`, `PUT /api/baskets/{customerId}/items/{cakeId}`, `DELETE /api/baskets/{customerId}/items/{cakeId}`
    - _Requirements: 3.1, 3.3, 3.4, 4.1, 4.2, 4.3, 4.4_

  - [ ] 4.7 Add the order-service error shape, exception advice, and request logging filter
    - Create `dto/ErrorResponse.java` and `config/GlobalExceptionHandler.java` mapping validation and type mismatch to 400 `VALIDATION_ERROR`, `EmptyBasketException` to 400 `BASKET_EMPTY`, `CakeNotFoundException` to 404 `CAKE_NOT_FOUND`, `BasketItemNotFoundException` to 404 `BASKET_ITEM_NOT_FOUND`, `OrderNotFoundException` to 404 `ORDER_NOT_FOUND`, `CakeUnavailableException` to 409 `CAKE_UNAVAILABLE`, `CatalogUnavailableException` to 503 `CATALOG_UNAVAILABLE`, and `Exception` to 500 `INTERNAL_ERROR` with an ERROR log
    - Create `service/exception/EmptyBasketException.java` and `service/exception/OrderNotFoundException.java` so the advice compiles ahead of the checkout work
    - Create `config/RequestLoggingFilter.java`
    - _Requirements: 3.4, 3.5, 3.6, 4.5, 5.4, 5.7, 12.1, 12.2, 12.3, 12.5_

  - [ ]* 4.8 Write unit and web layer tests for the basket
    - Create `BasketServiceTest` (Mockito `CatalogClient`): add, increment, update, remove, unit price capture, 404 and 409 mapping, basket left unchanged on every failure path
    - Create `BasketControllerTest` (`@WebMvcTest`): 200/201/400/404/409 statuses, quantity validation message, error body shape
    - Create `RequestLoggingFilterTest`
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 4.1, 4.2, 4.3, 4.4, 4.5, 12.1, 12.2, 12.5_

  - [ ]* 4.9 Write the basket total property test
    - Create `BasketTotalPropertyTest` (jqwik, `@Property(tries = 100)`)
    - **Property 1: Basket total equals the sum of line totals** — generate a 1..25 operation sequence (add, update, remove) over a small pool of cake identifiers with unit prices in [0.00, 999.99] scale 2 and quantities in [1, 20]; after every operation assert `basketTotal` equals the sum of `unitPrice * quantity` per stored item, each line rounded to two decimals HALF_UP
    - **Validates: Requirements 3.3, 4.3, 4.4, 4.6**

- [ ] 5. Order Service checkout and order completed event
  - [ ] 5.1 Implement CheckoutService and order retrieval
    - Create `service/CheckoutService.java` with a `@Transactional` `checkout` that rejects an empty basket, inserts the order with `total = basketTotal` and `status = CREATED`, copies every basket item into `order_items`, and deletes the basket items in the same transaction
    - Create `service/OrderService.java` with `getById` throwing `OrderNotFoundException` and `confirm` transitioning `CREATED -> CONFIRMED`
    - _Requirements: 5.1, 5.3, 5.4, 5.6, 5.7, 6.5_

  - [ ] 5.2 Implement the order DTOs and OrderController
    - Create `dto/CheckoutRequest.java` (`@NotBlank customerId`, `@NotBlank @Email customerEmail`), `dto/CheckoutResponse.java` (`orderId`, `orderTotal`, `status`), `dto/OrderResponse.java`, `dto/OrderItemResponse.java`, `dto/OrderStatusResponse.java`
    - Create `controller/OrderController.java`: `POST /api/orders` returning 201, `GET /api/orders/{orderId}` returning 200, `POST /api/orders/{orderId}/confirmation` returning 200
    - _Requirements: 5.2, 5.5, 5.6, 5.7, 6.5_

  - [ ] 5.3 Implement the order completed event and publisher
    - Create `messaging/OrderCompletedEvent.java` and `messaging/OrderCompletedItem.java` records carrying `orderId`, `customerId`, `customerEmail`, `orderTotal`, `createdAt`, and items (`cakeId`, `cakeName`, `unitPrice`, `quantity`)
    - Create `config/KafkaTopicConfig.java` declaring `order.completed` with 1 partition and replication factor 1
    - Create `messaging/OrderCompletedPublisher.java` sending with `KafkaTemplate<String, OrderCompletedEvent>` keyed by the order identifier, catching any send failure, logging `ERROR "Failed to publish order.completed for orderId={}"`, and returning normally
    - Wire the publish call after the checkout transaction commits so no event is published when it rolls back
    - _Requirements: 6.1, 6.2, 6.3, 6.4_

  - [ ]* 5.4 Write unit and web layer tests for checkout and publishing
    - Create `CheckoutServiceTest`: empty basket rejection, no event published when the transaction fails
    - Create `OrderControllerTest` (`@WebMvcTest`): 201 checkout body, missing and malformed email rejected naming the field, 404 for an unknown order, confirmation 200, error body shape
    - Create `OrderCompletedPublisherTest` with a throwing `KafkaTemplate` asserting the ERROR log carries the order identifier and no exception escapes
    - _Requirements: 5.2, 5.4, 5.5, 5.7, 6.3, 6.4, 6.5, 12.2, 12.3_

  - [ ]* 5.5 Write Testcontainers integration tests for the order service
    - Create `CheckoutTransactionIT` (PostgreSQL): order persisted and basket cleared atomically, rollback leaves both untouched
    - Create `OrderCompletedPublisherIT` (Kafka): exactly one record on `order.completed` keyed by the order identifier
    - Create `CrossDatabaseConfigIT`: startup fails with a logged configuration error when pointed at another service's database
    - Create `HealthEndpointIT`
    - _Requirements: 5.3, 6.1, 10.3, 12.4_

  - [ ]* 5.6 Write the checkout mirrors basket property test
    - Create `CheckoutMirrorsBasketPropertyTest` (jqwik, `@Property(tries = 100)`)
    - **Property 2: Checkout mirrors the basket and the order reads back unchanged** — generate a non-empty basket of 1..10 items, assert checkout creates exactly one order whose item multiset equals the basket items, `orderTotal` equals the pre-checkout `basketTotal`, status is `CREATED`, the basket is empty afterwards, and fetching the order returns the same items, total, and status
    - **Validates: Requirements 5.1, 5.3, 5.6**

  - [ ]* 5.7 Write the event JSON round trip property test
    - Create `OrderCompletedEventJsonPropertyTest` (jqwik, `@Property(tries = 100)`)
    - **Property 5: Order completed event survives a JSON round trip** — generate an event with 1..10 items, serialize with the configured Spring Kafka `JsonSerializer` and deserialize with the matching `JsonDeserializer`, assert equality including monetary scale on `orderTotal` and every `unitPrice`
    - **Validates: Requirements 6.2**

- [ ] 6. Notification Service (port 8084, `notification_db`)
  - [x] 6.1 Scaffold the notification-service Maven project and application configuration
    - Create `notification-service/pom.xml` with the JPA/Flyway/actuator/springdoc set plus `spring-kafka`, and test scope `spring-kafka-test`, `testcontainers` (`postgresql`, `kafka`), `jqwik:1.8.x`
    - Create `src/main/java/com/cakedelight/notification/NotificationServiceApplication.java`
    - Create `src/main/resources/application.yml`: `server.port: 8084`, env-var datasource, Flyway, `SPRING_KAFKA_BOOTSTRAP_SERVERS` with `JsonDeserializer` consumer settings, `groupId: notification-service`, trusted packages, actuator health groups, springdoc
    - _Requirements: 10.1, 12.4_

  - [ ] 6.2 Add the notifications migration, entity, and repository
    - Create `db/migration/V1__create_notifications.sql` with the `notifications` table, the partial unique index `uq_notifications_order_sent` on `(order_id) WHERE status = 'SENT'`, and `idx_notifications_order`
    - Create `domain/Notification.java`, `domain/NotificationStatus.java` (`SENT`, `FAILED`), `repository/NotificationRepository.java` with `existsByOrderIdAndStatus` and `findByOrderId`
    - _Requirements: 8.2, 8.4, 8.5, 8.6, 10.1_

  - [ ] 6.3 Implement the event record, the email channel stub, and NotificationService
    - Create `messaging/OrderCompletedEvent.java` and `messaging/OrderCompletedItem.java` as the consumer-side copy of the contract
    - Create `service/EmailChannel.java` composing the confirmation body from the order identifier, ordered items, and order total and logging the delivery to the event's contact details, with channel value `EMAIL`
    - Create `service/NotificationService.java` inserting a record with `orderId`, `channel`, `status`, `attemptedAt`, storing `FAILED` and logging ERROR with the order identifier and failure reason when the channel throws, and exposing `findByOrderId`
    - _Requirements: 8.1, 8.2, 8.3, 8.5_

  - [ ] 6.4 Implement the idempotent OrderCompletedListener
    - Create `messaging/OrderCompletedListener.java` (`@KafkaListener(topics = "order.completed", groupId = "notification-service")`): skip and log at INFO when a `SENT` record already exists, otherwise send and record the attempt; catch `DataIntegrityViolationException` from the partial unique index and log it so at most one successful confirmation exists per order
    - _Requirements: 8.1, 8.2, 8.4, 8.6_

  - [ ] 6.5 Implement the notification DTOs, controller, error handling, and request logging
    - Create `dto/NotificationResponse.java` and `controller/NotificationController.java` with `GET /api/notifications/orders/{orderId}` returning 200
    - Create `dto/ErrorResponse.java`, `config/GlobalExceptionHandler.java` (400 `VALIDATION_ERROR`, 500 `INTERNAL_ERROR` with ERROR log), `config/RequestLoggingFilter.java`
    - _Requirements: 8.5, 12.1, 12.2, 12.3, 12.5_

  - [ ]* 6.6 Write unit and web layer tests for the notification service
    - Create `OrderCompletedListenerTest` with a fake channel: confirmation content, stored record fields, `FAILED` status and ERROR log when the channel rejects, skip when a `SENT` record exists
    - Create `NotificationControllerTest` (`@WebMvcTest`): 200 and record list shape, error body shape
    - Create `RequestLoggingFilterTest`
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 12.2, 12.5_

  - [ ]* 6.7 Write the Testcontainers listener integration test
    - Create `NotificationListenerIT` (Kafka + PostgreSQL): publish an `OrderCompletedEvent`, assert it is consumed and one notification record is persisted
    - Create `HealthEndpointIT`
    - _Requirements: 8.1, 8.2, 12.4_

  - [ ]* 6.8 Write the notification idempotency property test
    - Create `NotificationIdempotencyPropertyTest` (jqwik, `@Property(tries = 100)`)
    - **Property 4: At most one successful notification per order** — generate an event with 1..10 items and a delivery multiplicity n in [1, 5], deliver the same event n times to the listener, assert exactly one `SENT` record for that order identifier and exactly one channel invocation
    - **Validates: Requirements 8.4, 8.6**

- [ ] 7. Checkpoint - All four services build and pass
  - Run `mvn clean verify` in `catalog-service`, `rating-service`, `order-service`, and `notification-service`. Ensure all tests pass, ask the user if questions arise.

- [ ] 8. API Gateway (port 8080)
  - [x] 8.1 Scaffold the api-gateway Maven project and route table
    - Create `api-gateway/pom.xml` with `spring-cloud-starter-gateway`, actuator, springdoc, and test scope `spring-boot-starter-test`, `reactor-test`, WireMock; pin the Spring Cloud BOM version
    - Create `src/main/java/com/cakedelight/gateway/ApiGatewayApplication.java`
    - Create `src/main/resources/application.yml`: `server.port: 8080`, actuator health groups, and routes declared in order — `/api/cakes/*/ratings/**` to the rating service, then `/api/cakes/**` to the catalog service, `/api/baskets/**` and `/api/orders/**` to the order service, `/api/notifications/**` to the notification service; no strip-prefix filter; downstream URIs read from environment variables
    - _Requirements: 9.1, 9.2, 12.4_

  - [ ] 8.2 Implement the gateway error handler
    - Create `dto/ErrorResponse.java` and `config/GlobalErrorHandler.java` implementing `ErrorWebExceptionHandler`: 404 `ROUTE_NOT_FOUND` for an unmatched path, 503 `SERVICE_UNAVAILABLE` naming the target service for `ConnectException` or `TimeoutException`, using the shared error shape
    - _Requirements: 9.3, 9.4, 12.2_

  - [ ]* 8.3 Write the gateway routing integration test
    - Create `GatewayRoutingIT` (`@SpringBootTest` + WireMock stubs per downstream): each route reaches the correct downstream with the path forwarded unchanged, and the ratings route wins over the broader cakes route
    - _Requirements: 9.2_

  - [ ]* 8.4 Write the gateway error handling integration test
    - Create `GatewayErrorHandlingIT`: 404 with `ROUTE_NOT_FOUND` for an unknown path, 503 with `SERVICE_UNAVAILABLE` naming the service for a closed downstream port
    - Create `HealthEndpointIT`
    - _Requirements: 9.3, 9.4, 12.4_

- [ ] 9. Local container stack
  - [ ] 9.1 Write one multi-stage Dockerfile per component
    - Create `catalog-service/Dockerfile`, `order-service/Dockerfile`, `rating-service/Dockerfile`, `notification-service/Dockerfile`, `api-gateway/Dockerfile` using the pinned `maven:3.9.9-eclipse-temurin-21` build stage and `eclipse-temurin:21.0.4_7-jre-alpine` runtime stage, each exposing its own port and baking in no configuration or credentials
    - _Requirements: 11.1, 11.5_

  - [ ] 9.2 Write docker-compose.yml for the full local stack
    - Create `docker-compose.yml` with `kafka` (`apache/kafka:3.7.1`, KRaft single node, topic auto-creation), four `postgres:16.4` databases (`catalog_db`, `order_db`, `rating_db`, `notification_db`), the four service builds, and `api-gateway` as the only published port (`8080:8080`)
    - Supply all service configuration through `environment:` blocks (`SPRING_DATASOURCE_*`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`, `CATALOG_SERVICE_URL`, gateway downstream URIs), add container health checks on `/actuator/health/readiness`, and gate services with `depends_on: condition: service_healthy`
    - _Requirements: 9.1, 10.1, 11.2, 11.5, 12.4_

- [ ] 10. Kubernetes manifests
  - [ ] 10.1 Write the namespace and infrastructure manifests
    - Create `k8s/namespace.yaml` for the `cake-delight` namespace
    - Create `k8s/postgres/` with one Deployment, Service, and PersistentVolumeClaim per database, plus `k8s/kafka/` with a single-node Kafka Deployment and Service
    - _Requirements: 10.1, 11.3_

  - [ ] 10.2 Write the per-component Deployment, Service, ConfigMap, and Secret manifests
    - Create `k8s/<component>/deployment.yaml`, `service.yaml`, `configmap.yaml`, and `secret.yaml` for `api-gateway`, `catalog-service`, `order-service`, `rating-service`, and `notification-service`
    - Deployments use 1 replica, the container port, `envFrom` the ConfigMap and Secret, a liveness probe on `/actuator/health/liveness`, and a readiness probe on `/actuator/health/readiness`; Services are `ClusterIP` except the gateway `NodePort`; Secrets are committed with placeholder values only
    - _Requirements: 9.1, 11.3, 11.4, 11.5, 12.4_

- [ ] 11. Documentation
  - [ ] 11.1 Write the API and event contract references
    - Create `docs/api.md` documenting every exposed endpoint with method, path, request body, response body, and status codes for all four services and the gateway route table
    - Create `docs/event-contract.md` with the `order.completed` topic settings, message key, consumer group, the field table, and a sample JSON payload
    - _Requirements: 6.2, 9.2, 12.2_

  - [ ] 11.2 Write the repository README
    - Create `README.md` with prerequisites, per-service `mvn clean package` and `mvn test` commands, `docker compose up --build`, `kubectl apply -f k8s/`, the required environment variables per component, and the browse -> filter -> basket -> checkout -> notification -> rating walkthrough with example requests through the gateway
    - _Requirements: 11.2, 11.3_

- [ ] 12. Final checkpoint - Full build green
  - Run `mvn clean verify` in all five component directories and confirm every unit, web slice, integration, and property test passes. Ensure all tests pass, ask the user if questions arise.

## Notes

- Sub-tasks marked with `*` are test tasks and can be skipped for a faster MVP.
- Every service is an independent Maven project; there is no parent aggregator pom.
- Each service keeps its own copy of `ErrorResponse`, `RequestLoggingFilter`, and the event record. No shared library.
- Out of scope items (auth, payments, inventory, outbox, dead-letter topics, circuit breakers, HPA, tracing, metrics dashboards, multi-channel notifications) have no tasks by design.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "2.1"] },
    { "id": 1, "tasks": ["1.2", "1.3", "2.2"] },
    { "id": 2, "tasks": ["1.4", "2.3"] },
    { "id": 3, "tasks": ["1.5", "1.6", "2.4"] },
    { "id": 4, "tasks": ["1.7", "1.8", "1.9", "2.5", "2.6", "2.7"] },
    { "id": 5, "tasks": ["4.1"] },
    { "id": 6, "tasks": ["4.2", "4.3"] },
    { "id": 7, "tasks": ["4.4", "4.5"] },
    { "id": 8, "tasks": ["4.6", "4.7"] },
    { "id": 9, "tasks": ["4.8", "4.9", "5.1"] },
    { "id": 10, "tasks": ["5.2", "5.3"] },
    { "id": 11, "tasks": ["5.4", "5.5", "5.6", "5.7", "6.1"] },
    { "id": 12, "tasks": ["6.2", "6.3"] },
    { "id": 13, "tasks": ["6.4", "6.5"] },
    { "id": 14, "tasks": ["6.6", "6.7", "6.8", "8.1"] },
    { "id": 15, "tasks": ["8.2", "9.1"] },
    { "id": 16, "tasks": ["8.3", "8.4", "9.2"] },
    { "id": 17, "tasks": ["10.1", "11.1"] },
    { "id": 18, "tasks": ["10.2", "11.2"] }
  ]
}
```
