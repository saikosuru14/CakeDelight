# Capstone brief traceability

Maps every numbered item in the capstone brief to the code, configuration, or document that satisfies
it, with an honest verification status.

Status column:

| | Meaning |
|---|---|
| **verified** | Exercised on a real run and the result observed — see [`DEMO.md`](DEMO.md) |
| **written** | Implemented and reviewed by hand, but never executed |
| **n/a** | Out of scope by the brief itself |

The single reason anything is *written* rather than *verified*: the Docker engine could not start on
the build machine (WSL2 unavailable, installing it requires administrator rights the account does not
have). Everything that needed a container runtime or a cluster is therefore unexecuted. Everything
else was run as five JVM processes plus Kafka on one host.

---

## 5. Functional scope

| Brief item | Where | Status |
|---|---|---|
| Browse available cakes | `GET /api/cakes` — `catalog/controller/CakeController` → `CakeService` → `CakeRepository` | verified — 24 cakes, 9 categories |
| Filter by name, category, price range | Same endpoint, four ANDed query params; `CakeQueryValidator` rejects `minPrice > maxPrice` | verified — `name=chocolate` → 4 hits; inverted range → `400 INVALID_PRICE_RANGE` |
| Add cakes to the basket | `POST /api/baskets/{customerId}/items` — `order/controller/BasketController` → `BasketService`, price and name snapshotted from `client/CatalogClient` | verified — `201` on insert, `200` on increment |
| View, update, remove basket items | `GET`, `PUT .../items/{cakeId}`, `DELETE .../items/{cakeId}` | verified — all `200`; DELETE returns the recalculated basket |
| Complete checkout, create an order | `POST /api/orders` — `order/service/CheckoutService`, one transaction: insert order, copy lines, clear basket | verified — order `58a14309-…`, total `197.49`, `CREATED` |
| Submit ratings for cakes | `POST /api/cakes/{cakeId}/ratings` — `rating/controller/RatingController` → `RatingService` | verified — average `4.5` over `2` ratings |
| Order confirmation notification after checkout | `order.completed` on Kafka → `notification/messaging/OrderCompletedListener` → `NotificationService` → `EmailChannel` | verified — `SENT` record ~2 s after checkout |

## 6. Microservices design

### 6.1 Cake Catalog

| Brief item | Where | Status |
|---|---|---|
| Name, description, category, price, availability, image reference | `catalog/domain/Cake` — all six fields; Flyway `V1__*.sql` is the schema of record | verified |
| APIs to list cakes and retrieve one | `GET /api/cakes`, `GET /api/cakes/{cakeId}` | verified |
| Filter by name, category, price range | `CakeRepository` specification-style query, `CakeQueryValidator` for the range check | verified |

### 6.2 Order

| Brief item | Where | Status |
|---|---|---|
| Add, update, remove basket items | `BasketService` + `BasketController` | verified |
| Display basket contents and calculate totals | `BasketResponse` with per-line `lineTotal` and `basketTotal`, `BigDecimal` scale 2 `HALF_UP` | verified |
| Create orders at checkout, maintain order status | `CheckoutService`; `domain/OrderStatus` = `CREATED` → `CONFIRMED` via `POST /api/orders/{orderId}/confirmation` | verified |
| Publish an order completion event after checkout | `messaging/OrderCompletedPublisher`, `@TransactionalEventListener(AFTER_COMMIT)` so nothing publishes from a rolled-back checkout | verified |

### 6.3 Rating

| Brief item | Where | Status |
|---|---|---|
| Submit ratings | `POST /api/cakes/{cakeId}/ratings`, score `@Min(1) @Max(5)` | verified |
| Store and retrieve per-product ratings | `rating/domain/Rating`, `GET /api/cakes/{cakeId}/ratings` | verified |
| Calculate and expose average ratings | `GET /api/cakes/{cakeId}/ratings/average`, mean rounded to one decimal `HALF_UP`, `null` with count `0` when unrated | verified |

### 6.4 Notification

| Brief item | Where | Status |
|---|---|---|
| Listen for order completion events | `OrderCompletedListener`, `@KafkaListener(topics = "order.completed", groupId = "notification-service")` | verified — broker logged `Stabilized group notification-service generation 1 with 1 members` |
| Send confirmation by email, SMS, or in-app | `NotificationChannel` with two implementations, `EmailChannel` (default) and `InAppChannel`, selected by `cakedelight.notification.channel`. Delivery is a logged stub — no SMTP server or push provider is contacted | verified (EMAIL path) |
| Maintain delivery status | `domain/Notification` with `SENT` / `FAILED`, read back via `GET /api/notifications/orders/{orderId}` | verified |

The brief says "email, SMS, or in-app" — one of three, not all three. One channel is selected at
runtime; multi-channel fan-out is deliberately out of scope.

## 7. Architecture

### Components

| Brief component | Implementation | Status |
|---|---|---|
| Client application or user interface | `web-ui/` — plain HTML, CSS, vanilla JS, no build step. Calls only its own origin under `/api/`, which nginx proxies to the gateway | verified via `web-ui/dev-server.js`; the nginx image is written but unbuilt |
| API Gateway | `api-gateway/` — Spring Cloud Gateway on WebFlux, four routes, no `StripPrefix` | verified |
| Cake Catalog Microservice | `catalog-service/` :8081 | verified |
| Order Microservice | `order-service/` :8082 | verified |
| Rating Microservice | `rating-service/` :8083 | verified |
| Notification Microservice | `notification-service/` :8084 | verified |
| Databases owned by respective services | PostgreSQL 16 in `docker-compose.yml` and `k8s/postgres/`, one database and one credential Secret per service | **written** — the verified run used H2 in-memory under the `local` profile |
| Message broker | Kafka 3.7.1, single-node KRaft, topic `order.completed`, 1 partition | verified |

### Characteristics

| Brief item | How | Status |
|---|---|---|
| Independently buildable | Five separate Maven projects, no parent aggregator pom. `mvn clean package` in each | verified |
| Independently deployable | One Dockerfile, one Deployment, one Service, one ConfigMap, one Secret per component | **written** |
| Independently scalable | Six HPAs — the four services, the gateway, the UI. Databases and Kafka deliberately excluded, see [`k8s/README.md`](../k8s/README.md#scaling) | **written** |
| Clearly defined APIs and messaging contracts | [`api.md`](api.md) for HTTP, [`event-contract.md`](event-contract.md) for the event. No shared library between publisher and consumer — the JSON field names are the contract | verified |
| Containerized with Docker | Six multi-stage Dockerfiles, all base tags pinned, `.dockerignore` per build context | **written** — never built |
| Deployment, scaling, service discovery via Kubernetes | `k8s/`, 9 component directories, namespace `cake-delight`. Discovery is Kubernetes Service DNS; no Eureka or Consul | **written** — never applied |
| Fault tolerance | Bounded exponential-backoff retry on the one cross-service read (`CatalogClient`, config under `catalog.service.retry.*`), retrying only transient failures; GET-only retry at the gateway; readiness probes gate traffic on database availability; a broker failure cannot fail a checkout | partly verified — the retry code paths ran, but no failure was injected to force a retry |
| Retries | `@Retryable` on `CatalogClient.fetchCake`, `@Recover` for the exhausted case plus one that rethrows non-retryable exceptions unchanged | verified — the missing second `@Recover` was a real bug, found and fixed |
| Logging | `RequestLoggingFilter` in each of the four services: correlation id in and out, method, path, status, duration | verified |
| Basic monitoring | Actuator `health` with `liveness` and `readiness` probe groups, plus `info`, `metrics`, and `prometheus`, on all five Spring components. `micrometer-registry-prometheus` 1.13.4 | verified |

## 8. Expected deliverables

| Deliverable | Artifact | Status |
|---|---|---|
| Source code for all microservices | `catalog-service/`, `order-service/`, `rating-service/`, `notification-service/`, `api-gateway/`, `web-ui/` | present |
| API documentation for exposed endpoints | [`api.md`](api.md) — every endpoint, every field, every error code. Plus springdoc Swagger UI at `/swagger-ui.html` on each service | present |
| Dockerfiles for each service | six, one per component, multi-stage where there is something to compile | present, unbuilt |
| Kubernetes deployment and service configuration | `k8s/`, with [`k8s/README.md`](../k8s/README.md) for procedure and known gaps | present, unapplied |
| Database schema / data model | Flyway migrations per service under `src/main/resources/db/migration` (PostgreSQL, source of truth); H2 equivalents under `db/h2` for the `local` profile | present, PostgreSQL DDL unexecuted |
| Message/event contract | [`event-contract.md`](event-contract.md) — topic, key, serializers, payload, publish and consume semantics, and what is safe to change | present |
| Setup and execution instructions | [`../README.md`](../README.md) — three paths, each labelled with whether it has actually been run | present |
| Short demonstration of the end-to-end flow | [`DEMO.md`](DEMO.md) — written walkthrough with captured status codes, ids, and totals. Drivable live from the web UI or the Postman collection in `postman/` | present; no screen recording |

## 9. Evaluation criteria

| Area | Evidence |
|---|---|
| Microservices design | Four services, one bounded context and one database each. Exactly one synchronous cross-service call in the whole system (`order → catalog`, read-only). No cross-service writes, no shared database, no shared library |
| API implementation | Plural resource paths, DTOs at every HTTP boundary and no JPA entity crossing it, Jakarta Bean Validation with `@Valid`, one `{ code, message, timestamp, path }` error shape across all five components, 12 distinct error codes |
| Event handling | Transactional publish after commit, JSON payload with no type headers and an explicitly declared consumer type, two-layer idempotency guard (listener pre-check plus a partial unique index) |
| Containerization | Six pinned multi-stage Dockerfiles, full `docker-compose.yml` with health-gated `depends_on`, all config through environment variables, **but never built** |
| Kubernetes deployment | 9 component directories, ClusterIP everywhere except two NodePorts, ConfigMap/Secret split, probe groups wired to the actuator, six HPAs with the CPU requests they need, **but never applied** |
| Reliability and maintainability | Bounded retry, per-request correlation logging, actuator health and metrics, layered packages, `BigDecimal` money throughout |
| End-to-end demo | All seven steps run and captured: browse → filter → basket → adjust → checkout → event → notification → rating |

---

## Honest gaps

Things a reviewer should know before grading:

1. **No container or cluster has ever run this.** `docker compose up` and `kubectl apply` were never
   executed. The Dockerfiles and manifests are hand-reviewed but schema-unvalidated: even
   `kubectl create --dry-run=client` needs to reach an API server to resolve REST mappings, so there
   was no offline way to check them.
2. **The verified run used H2, not PostgreSQL.** The PostgreSQL migrations under `db/migration` have
   never been applied by a live Flyway. One consequence is concrete: H2 cannot express the partial
   unique index `uq_notifications_order_sent`, so the second idempotency guard is PostgreSQL-only and
   was not exercised.
3. **Test coverage is thin by request.** Only `catalog-service` has tests — 2 classes, 32 assertions.
   The brief lists no testing deliverable and the project owner explicitly excluded test work from
   scope, so this is a deliberate omission rather than an oversight, but it is an omission.
4. **Notification delivery is a stub.** No SMTP server and no push provider is contacted; the channel
   logs and records `SENT`. Wiring a real provider is not in the brief's functional scope.
5. **No authentication anywhere.** Every endpoint is open, and the order read deliberately omits
   `customerEmail` for that reason. Authentication is explicitly out of scope for this increment.
6. **Failure injection was not performed.** The retry and 503 paths are implemented and unit-reachable
   but no service was killed mid-request to observe `CATALOG_UNAVAILABLE` or `SERVICE_UNAVAILABLE` in
   the wild.
7. **Kubernetes PVCs assume a default StorageClass.** They name none, so on a cluster without a
   default provisioner they stay `Pending` and the databases never start.
