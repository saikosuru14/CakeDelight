# Cake Delight — Project Overview

High-level guide to what this project is, how the pieces fit together, what each service owns, what
lives in each database, and what it is built from.

For depth, see [`api.md`](api.md) (every endpoint), [`data-model.md`](data-model.md) (every column),
[`event-contract.md`](event-contract.md) (the Kafka payload), and [`DEMO.md`](DEMO.md) (the flow with
captured evidence).

---

## 1. What it is

A cloud-native microservices application delivering one complete customer journey for an online cake
shop: browse the catalogue, filter it, build a basket, check out, get a confirmation notification, and
rate a cake.

Six deployable components. Four business microservices, each owning its own database, plus a gateway
that is the single client entry point and a browser client that holds no data at all.

| Component | Port | Owns | Database |
|---|---|---|---|
| `web-ui` | 8090 | nothing | — |
| `api-gateway` | 8080 | routing config | — |
| `catalog-service` | 8081 | cake products | `catalog_db` |
| `order-service` | 8082 | baskets and orders | `order_db` |
| `rating-service` | 8083 | ratings | `rating_db` |
| `notification-service` | 8084 | notification records | `notification_db` |

Plus two pieces of infrastructure: **PostgreSQL 16**, four separate databases, and **Kafka**, a single
broker carrying one topic.

## 2. How it fits together

```
                        ┌───────────┐
   browser ────────────►│  web-ui   │  nginx: serves the static client,
                        │   :8090   │  proxies /api/ so the browser stays same-origin
                        └─────┬─────┘
                              │ /api/**
                        ┌─────▼──────┐
                        │ api-gateway│  the ONLY client entry point
                        │   :8080    │  route table, no logic, no database
                        └──┬───┬───┬─┴──┐
              ┌────────────┘   │   │    └──────────────┐
              │                │   │                   │
      ┌───────▼──────┐  ┌──────▼───┐  ┌────────────┐  ┌▼──────────────────┐
      │catalog :8081 │◄─┤order:8082│  │rating :8083│  │notification :8084 │
      └───────┬──────┘  └────┬──┬──┘  └─────┬──────┘  └────────┬─────────┘
              │    REST      │  │           │                  │
              │  (sync, the  │  │           │                  │
              │  only one)   │  │           │                  │
        ┌─────▼─────┐  ┌─────▼──▼──┐  ┌─────▼─────┐   ┌────────▼────────┐
        │ catalog_db│  │  order_db │  │ rating_db │   │ notification_db │
        └───────────┘  └─────┬─────┘  └───────────┘   └────────▲────────┘
                             │                                 │
                             │  publish            consume     │
                             └────────►  Kafka  ───────────────┘
                                      order.completed
                                        1 partition
```

Two, and only two, ways the services talk:

**Synchronous — one call in the entire system.** When a cake is added to a basket, the Order Service
reads that cake's price and availability from the Catalog Service over REST. Nothing else crosses a
service boundary synchronously, and no service ever writes to another service's data.

**Asynchronous — one topic.** After a checkout transaction commits, the Order Service publishes an
`order.completed` event to Kafka. The Notification Service consumes it and sends the confirmation.
The two never call each other.

Three rules hold everywhere, and they are what make this a microservices architecture rather than a
distributed monolith:

- **One database per service, and only its owner touches it.** No shared schema, no cross-database
  join, no second service reading another's tables.
- **Cross-service reads go through the owning service's API.** Cross-service writes never happen.
- **No shared library.** The publisher and consumer each keep their own copy of the event record;
  every service keeps its own `ErrorResponse`. The contract is the JSON, documented in
  [`event-contract.md`](event-contract.md), not a Java type.

## 3. The customer journey, end to end

| # | Step | Request | Handled by | Persisted |
|---|---|---|---|---|
| 1 | Browse | `GET /api/cakes` | catalog | nothing |
| 2 | Filter | `GET /api/cakes?name=&category=&minPrice=&maxPrice=` | catalog | nothing |
| 3 | Add to basket | `POST /api/baskets/{customerId}/items` | order → catalog | basket line |
| 4 | View / update / remove | `GET`, `PUT`, `DELETE` on the basket | order | basket line |
| 5 | Checkout | `POST /api/orders` | order → Kafka | order + items, basket cleared |
| 6 | Notification | `GET /api/notifications/orders/{orderId}` | notification ← Kafka | one row per attempt |
| 7 | Rate | `POST /api/cakes/{cakeId}/ratings` | rating | rating |

The interesting steps:

**Step 3 reads before it writes.** The Order Service fetches the cake from the Catalog Service first,
and only then inserts. Because of that ordering, every rejection — unknown cake, unavailable cake,
catalog down — leaves the basket exactly as it was. The cake's name and price are *snapshotted* onto
the basket line, so a later price change in the catalogue cannot silently re-price someone's basket.

**Step 5 is one transaction.** Insert the order, copy every basket line into `order_items`, clear the
basket. The event is published only *after* that transaction commits, so a rolled-back checkout emits
nothing and a dead broker cannot fail a checkout.

**Step 6 is asynchronous, and visibly so.** The customer gets their `201` immediately; the
confirmation is written a second or two later by a different service against a different database.
A notification read fired instantly after checkout can legitimately return an empty array.

## 4. What each service does

### Catalog Service — port 8081, owns `catalog_db`

The product catalogue. Read-only from the outside: there is no endpoint to create or edit a cake, so
the seeded data is the catalogue.

- `GET /api/cakes` — list with filtering and paging
- `GET /api/cakes/{cakeId}` — one cake

Filtering combines name, category, `minPrice`, and `maxPrice` with AND in a single null-tolerant
query, so any subset of the four works and omitted parameters simply drop out. Name matching is a
case-insensitive substring; category is case-insensitive equality. An inverted price range is rejected
rather than quietly returning nothing.

Seeded with 24 cakes across 9 categories, 4 of them deliberately unavailable so the conflict path is
reachable without editing data.

### Order Service — port 8082, owns `order_db`

The busiest service, and the only one that calls another service.

- `POST /api/baskets/{customerId}/items` — add, or increment if the line exists
- `GET /api/baskets/{customerId}` — the basket with line totals and a basket total
- `PUT /api/baskets/{customerId}/items/{cakeId}` — replace a quantity
- `DELETE /api/baskets/{customerId}/items/{cakeId}` — remove a line
- `POST /api/orders` — checkout
- `GET /api/orders/{orderId}` — read an order
- `POST /api/orders/{orderId}/confirmation` — move `CREATED` → `CONFIRMED`

Notable behaviour: a basket is not an entity — it is just the set of basket lines sharing a customer
id, which is why reading an unknown customer's basket is a `200` with an empty list rather than a
`404`. Adding returns `201` for a new line and `200` for an increment. Checkout takes no item list, so
a client cannot supply its own prices or quantities. All money arithmetic lives in one place, so the
total cannot disagree with itself between endpoints.

### Rating Service — port 8083, owns `rating_db`

- `POST /api/cakes/{cakeId}/ratings` — submit a score of 1–5
- `GET /api/cakes/{cakeId}/ratings` — every rating for a cake
- `GET /api/cakes/{cakeId}/ratings/average` — mean to one decimal place, plus a count

Deliberately does **not** verify the cake exists against the Catalog Service. Ratings are accepted for
any identifier. That keeps rating availability independent of catalogue availability; the trade is
that an orphan rating is possible. An unrated cake returns a `null` average with count `0`, not a
`404`. The average is computed by query and never stored, so there is no denormalized value to go
stale.

### Notification Service — port 8084, owns `notification_db`

The only event-driven service. It consumes `order.completed`, composes the confirmation, delivers it,
and records the attempt.

- `GET /api/notifications/orders/{orderId}` — every delivery attempt for an order

It exposes **no write endpoint at all**: records are created only by the Kafka listener. The response
is always a list, because failed attempts accumulate while at most one successful one can exist.

Delivery goes through a channel abstraction with two implementations, `EMAIL` (the default) and
`IN_APP`, one selected at runtime. Delivery itself is a logged stub — no SMTP server or push provider
is contacted.

"At most one successful confirmation per order" is guarded twice: the listener skips the event if a
`SENT` record already exists, and a partial unique index rejects a second `SENT` row if two deliveries
race past that check.

### API Gateway — port 8080

Spring Cloud Gateway on WebFlux. A route table and nothing else: no database, no business logic.

| Order | Path predicate | Target |
|---|---|---|
| 0 | `/api/cakes/*/ratings/**` | rating-service |
| 1 | `/api/cakes/**` | catalog-service |
| 2 | `/api/baskets/**`, `/api/orders/**` | order-service |
| 3 | `/api/notifications/**` | notification-service |

Two details carry real weight. The ratings route must be evaluated before `/api/cakes/**`, which is
why the order values are explicit — reverse them and every rating request lands on the Catalog
Service. And no route strips a path prefix, so a service receives byte-for-byte the path the browser
sent.

Every downstream URL comes from an environment variable, so no hostname is baked into the image.

### Web UI — port 8090

The client application. Plain HTML, CSS, and vanilla JavaScript with no build step and no framework.
It holds no data and no business rules — every read and write goes through the gateway.

nginx does two jobs: serve the static files and reverse-proxy `/api/` to the gateway. Because the
browser therefore talks to a single origin, the UI needs no CORS handling of its own.

## 5. Database tables

Four databases. Column-level detail is in [`data-model.md`](data-model.md).

### `catalog_db`

| Table | Purpose | Key columns |
|---|---|---|
| `cakes` | the product catalogue | `id`, `name`, `description`, `category`, `price`, `available`, `image_url` |

Three indexes, two of them on `lower(name)` and `lower(category)` because the filter API matches
case-insensitively and could not otherwise use an index.

### `order_db`

| Table | Purpose | Key columns |
|---|---|---|
| `basket_items` | one row per cake in a customer's basket | `id`, `customer_id`, `cake_id`, `cake_name`, `unit_price`, `quantity` |
| `orders` | one row per checkout | `id`, `customer_id`, `customer_email`, `total`, `status`, `created_at` |
| `order_items` | the basket lines copied at checkout | `id`, `order_id`, `cake_id`, `cake_name`, `unit_price`, `quantity` |

`UNIQUE (customer_id, cake_id)` on `basket_items` is what makes a repeat add an increment rather than a
duplicate line. `order_items.order_id → orders.id` is the only foreign key in the entire project,
because it is the only relationship where both sides are owned by the same service.

### `rating_db`

| Table | Purpose | Key columns |
|---|---|---|
| `ratings` | one row per submitted rating | `id`, `cake_id`, `customer_id`, `score`, `created_at` |

`CHECK (score BETWEEN 1 AND 5)` is the database-level guarantee that an average can never fall outside
the valid range. There is intentionally no uniqueness on `(cake_id, customer_id)` — a customer may rate
the same cake more than once.

### `notification_db`

| Table | Purpose | Key columns |
|---|---|---|
| `notifications` | one row per delivery **attempt** | `id`, `order_id`, `channel`, `status`, `attempted_at` |

Carries a **partial** unique index, `UNIQUE (order_id) WHERE status = 'SENT'`: unlimited `FAILED`
attempts per order, at most one `SENT`.

### Why almost no foreign keys

`basket_items.cake_id`, `order_items.cake_id`, `ratings.cake_id`, and `notifications.order_id` are
plain UUID columns with no `REFERENCES` clause. The rows they point at live in a *different database
owned by a different service*, so a foreign key is not merely undesirable — it is impossible. This is
the database-per-service boundary made literal, and the snapshotting of `cake_name` and `unit_price`
is what makes it safe.

## 6. Tech stack

| Concern | Choice |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.3.4 |
| Gateway | Spring Cloud Gateway (Spring Cloud 2023.0.3), reactive / WebFlux |
| Build | Maven — five independent projects, no parent aggregator |
| Database | PostgreSQL 16 (production), H2 2.2.224 (local and test) |
| Data access | Spring Data JPA / Hibernate, `ddl-auto: validate` |
| Schema migration | Flyway 10.17.2 |
| Message broker | Apache Kafka 3.7.1 (KRaft, no ZooKeeper) via Spring for Apache Kafka |
| Resilience | Spring Retry 2.0.10 — bounded exponential backoff |
| API docs | springdoc-openapi 2.6.0, Swagger UI at `/swagger-ui.html` |
| Monitoring | Spring Boot Actuator + Micrometer 1.13.4, Prometheus scrape endpoint |
| Testing | JUnit 5, Spring Boot Test, Testcontainers 1.20.1, jqwik 1.8.5 |
| Client | HTML, CSS, vanilla JavaScript — no framework, no build step |
| Containers | Docker, multi-stage builds, all base image tags pinned |
| Orchestration | Kubernetes — Deployment, Service, ConfigMap, Secret, HPA per component |

Every version is pinned explicitly. No dependency and no container image uses a floating tag.

## 7. Dependencies per component

Common to all four business services: `spring-boot-starter-web`, `-validation`, `-data-jpa`,
`-actuator`, `micrometer-registry-prometheus`, `flyway-core`, `flyway-database-postgresql`,
`postgresql`, `h2`, `springdoc-openapi-starter-webmvc-ui`. Test scope adds
`spring-boot-starter-test`, Testcontainers, and `jqwik`.

On top of that:

| Component | Additional dependencies | Why |
|---|---|---|
| `catalog-service` | — | plain CRUD reads; nothing extra needed |
| `order-service` | `spring-kafka`, `spring-retry`, `spring-boot-starter-aop` | publishes the event; retries the catalog read. `spring-retry` needs AOP proxying to honour `@Retryable` |
| `rating-service` | — | |
| `notification-service` | `spring-kafka` | consumes the event |
| `api-gateway` | `spring-cloud-starter-gateway`, `springdoc-openapi-starter-webflux-ui`, `spring-boot-starter-actuator` | reactive stack. **No** JPA, no database driver, no Kafka — it owns no data |
| `web-ui` | none | no package manager, no build step |

Runtime infrastructure images: `postgres:16.4`, `apache/kafka:3.7.1`, `nginx:1.27-alpine`, and
`eclipse-temurin` JRE Alpine for the service images.

## 8. Cross-cutting concerns

**One error shape everywhere.** All five Spring components return the same body on every error:
`{ code, message, timestamp, path }`. The four services produce it from a `@RestControllerAdvice`; the
gateway needs an `ErrorWebExceptionHandler` instead, because it runs on WebFlux where controller advice
never sees the error. Twelve distinct error codes are defined and documented.

**Validation at the edge.** Jakarta Bean Validation annotations plus `@Valid` on every request body,
so a malformed payload is rejected before any business logic runs.

**Money is never a float.** `BigDecimal` at scale 2 with `HALF_UP`, backed by `NUMERIC(12,2)` columns.
No `double` touches a price anywhere.

**Retry, bounded, and only where safe.** The gateway retries `GET` only — replaying a `POST` could
create two orders. The Order Service retries its catalog read only on transient failures; a `404` or an
unavailable cake is a definite answer and is never retried.

**Logging with correlation.** Each service has a request filter that reads or generates an
`X-Request-Id`, puts it in the logging context, echoes it back, and logs method, path, status, and
duration.

**Health and monitoring.** Actuator on every component with separate `liveness` and `readiness` probe
groups. Readiness on the four services includes a database check, so a pod stays out of its Service's
endpoint list until its database is reachable and migrations have finished. Metrics are exposed in
Prometheus format; no Prometheus server or dashboard is deployed.

**Configuration entirely from the environment.** No credential appears in any `application.yml`,
Dockerfile, or committed manifest. Under Compose, values come from `environment:` blocks; under
Kubernetes, non-secret values come from ConfigMaps and credentials from Secrets. Committed Secrets
carry `REPLACE_ME` placeholders.

## 9. How to run it

Full instructions are in the [README](../README.md). In short, three paths:

| Path | What it uses | Status |
|---|---|---|
| Docker Compose | containers, PostgreSQL, Kafka | written, **never executed** |
| Local JVM processes | `java -jar`, H2 in-memory, local Kafka | **verified working**, full journey |
| Kubernetes | manifests in `k8s/` | written, **never applied** |

Stated plainly: the container and cluster paths have never been run. The build machine could not start
a Docker engine — WSL2 is absent and installing it needs administrator rights that were unavailable —
so no image was built and no manifest was applied. Every YAML file parses cleanly, but none has been
schema-validated against the Kubernetes API.

The local JVM path *has* been run end to end, including Kafka, with the results captured in
[`DEMO.md`](DEMO.md).

## 10. What is deliberately not here

Scope was kept to the brief. These were all considered and left out on purpose:

- Authentication, authorization, JWT
- Payment processing
- Inventory or stock decrement — `available` is a flag, not a count
- Transactional outbox, exactly-once delivery, dead-letter queues, schema registry
- Circuit breakers and bulkheads — retry is in scope, failure isolation is not
- Service mesh, distributed tracing, metrics dashboards
- Multi-channel notification fan-out — one channel is active at a time
- Real email or SMS delivery
