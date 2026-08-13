# End-to-end demonstration

Capstone deliverable: *"Short demonstration of the end-to-end application flow."*

This is a written demonstration with captured evidence, not a screen recording. Every status code,
identifier, total, and count below was observed on a real run of the stack. Where a value is
illustrative rather than captured it says so.

## What was run

| | |
|---|---|
| Path | **Local JVM processes** — five `java -jar` processes on one Windows host, Spring profile `local` |
| Persistence | H2 in-memory, one schema per service, migrated by Flyway from `src/main/resources/db/h2` |
| Broker | Apache Kafka 3.7.1, standalone KRaft, single node on `localhost:9092` |
| Client | `web-ui` served by `web-ui/dev-server.js` on `:8090`, proxying `/api/` to the gateway |
| Entry point | API Gateway on `http://localhost:8080` — every call below goes through it |

Docker Compose and Kubernetes were **not** used for this run. No container runtime or Kubernetes
cluster was available in the development environment, so `docker compose up` and `kubectl apply` have
not been executed. The manifests and Dockerfiles are written and reviewed but unexecuted — see
[`k8s/README.md`](../k8s/README.md).

That distinction matters for grading and it is stated plainly: the *application flow* is
demonstrated, the *container and cluster packaging* is not.

Ports 8081–8084 were listening on localhost in this run because the services were plain JVM
processes. Under Compose and Kubernetes they are not published; the gateway is the only entry point
either way, and the walkthrough below only ever calls `:8080`.

---

## The flow, with captured evidence

Seven steps: browse, filter, basket, adjust, checkout, notification, rating.

### 1. Browse the catalog

```
GET http://localhost:8080/api/cakes
```

**Captured:** `200`, `totalElements: 24`, default page `0` size `20`, so the first response carries
20 of 24 items.

The seeded catalogue: **24 cakes across 9 categories**, prices from **2.50** to **149.99**, of which
**4 are marked `available: false`**. Those four exist to make the `409 CAKE_UNAVAILABLE` path in step 3
reachable without editing data.

The gateway forwarded this to `catalog-service:8081` with no path rewriting — the route applies no
`StripPrefix`, so the service saw `/api/cakes` exactly as the browser sent it.

### 2. Filter

```
GET http://localhost:8080/api/cakes?name=chocolate
```

**Captured:** `200`, **4 matches**. Name matching is a case-insensitive substring, so `chocolate`
also matched names where it is not the leading word.

Filters combine with AND — `name`, `category`, `minPrice`, `maxPrice` — and an inverted range is
rejected rather than silently returning nothing:

```
GET http://localhost:8080/api/cakes?minPrice=50&maxPrice=10
```

**Captured:** `400`, `code: INVALID_PRICE_RANGE`.

### 3. Add to the basket

```
POST http://localhost:8080/api/baskets/{customerId}/items
{ "cakeId": "11111111-1111-4111-8111-000000000004", "quantity": 2 }
```

**Captured:** `201` on the first call. Repeating the same call returned **`200`**, with the existing
line's quantity increased rather than a duplicate line inserted. The two statuses are the
demonstration: `201` means a line was created, `200` means one was incremented.

This is also the one **synchronous cross-service call** in the system. Before writing anything, the
Order Service reads the cake from the Catalog Service through `CatalogClient`
(`GET /api/cakes/{cakeId}`, 5 s connect and read timeouts, with bounded exponential-backoff retry on
transient failures only). Name and unit price are snapshotted onto the basket line at insert, which is
why a later catalogue price change does not silently re-price a basket.

Because the read happens first, every rejection leaves the basket untouched:

| Attempt | Captured result |
|---|---|
| Unknown cake id | `404` `CAKE_NOT_FOUND` |
| `11111111-1111-4111-8111-000000000006` (Lemon Drizzle Cupcake, `available: false`) | `409` `CAKE_UNAVAILABLE` |
| `quantity: 0` or a missing `cakeId` | `400` `VALIDATION_ERROR` |

The `409` case is worth calling out because it initially returned `500`. Spring Retry routes *every*
terminal failure through a `@Recover` method, and only `CatalogUnavailableException` had one, so
non-retryable exceptions such as `CakeUnavailableException` were wrapped into `ExhaustedRetryException`
and surfaced as `INTERNAL_ERROR`. Resolved by adding a
`@Recover rethrowNonRetryable(RuntimeException, UUID)` in `CatalogClient`.

### 4. View and adjust the basket

```
GET    http://localhost:8080/api/baskets/{customerId}
PUT    http://localhost:8080/api/baskets/{customerId}/items/{cakeId}   { "quantity": 1 }
DELETE http://localhost:8080/api/baskets/{customerId}/items/{cakeId}
```

**Captured:** all `200`. `PUT` replaces the quantity rather than adding to it. `DELETE` returns the
recalculated basket in the body — a `200` with content, not a `204` — so the UI never has to re-fetch
to redraw the total. A customer with nothing stored gets `200` with an empty `items` array and
`basketTotal: 0.00`, never a `404`.

`basketTotal` is the sum of the per-line rounded `lineTotal` values, `BigDecimal` scale 2 `HALF_UP`
throughout. No `double` touches a price anywhere in the codebase.

### 5. Check out

```
POST http://localhost:8080/api/orders
{ "customerId": "...", "customerEmail": "..." }
```

**Captured:**

```json
{
  "orderId": "58a14309-4205-4508-baab-dffd4d1d3aa3",
  "orderTotal": 197.49,
  "status": "CREATED"
}
```

`201`. No item list is sent — the order is built from whatever is stored for that `customerId`. In one
transaction: the order is inserted with `total = basketTotal` and `status = CREATED`, every basket line
is copied into `order_items`, and the basket is cleared.

**Captured:** an immediate `GET /api/baskets/{customerId}` after checkout returned an empty `items`
array and `basketTotal: 0.00`. The basket really is emptied by the same transaction that creates the
order, so a double-submit cannot produce a second order from the same lines — the second attempt gets
`400 BASKET_EMPTY`.

**Captured:** `POST /api/orders` against an already-empty basket returned `400`, `code: BASKET_EMPTY`,
and created no order.

The exact line items behind the `197.49` were not recorded, so they are not reproduced here. The total,
the order id, and the status are as captured.

### 6. The event and the notification

This is the asynchronous half of the system, and the only place the services are decoupled at runtime.

`CheckoutService` never touches Kafka. It raises the payload as a Spring application event inside its
transaction; `OrderCompletedPublisher` picks it up with
`@TransactionalEventListener(phase = AFTER_COMMIT)` and publishes to `order.completed`, keyed by the
order id. So a dead broker cannot fail a checkout, and an uncommitted checkout cannot emit an event.
Payload contract: [`event-contract.md`](event-contract.md).

**Captured** from the Kafka broker log, confirming the consumer group formed:

```
Stabilized group notification-service generation 1 with 1 members
```

**Captured** from the Notification Service, roughly **2 seconds** after the checkout returned:

```
GET http://localhost:8080/api/notifications/orders/58a14309-4205-4508-baab-dffd4d1d3aa3
```

returned one record with `channel: "EMAIL"` and `status: "SENT"`.

The ~2 s gap is the demonstration of loose coupling: the customer got their `201` immediately, and the
confirmation happened afterwards on a different thread in a different service reading a different
database. Nothing in the checkout path waited for it.

Delivery is guarded twice for "at most one successful confirmation per order": the listener skips the
event if a `SENT` record already exists, and a partial unique index
`uq_notifications_order_sent on (order_id) WHERE status = 'SENT'` catches two deliveries racing past
that check.

> **Caveat, stated because this run used H2:** H2 cannot express a partial unique index, so the second
> guard is a **PostgreSQL-only** protection. Under the `local` and `test` profiles only the listener's
> pre-send check is active. The run above exercised the pre-send check, not the index.

Then, optionally, the customer acknowledges:

```
POST http://localhost:8080/api/orders/58a14309-4205-4508-baab-dffd4d1d3aa3/confirmation
```

`200` with `status: "CONFIRMED"`. Idempotent — confirming twice answers `CONFIRMED` again rather than
a conflict.

### 7. Rate the cake

```
POST http://localhost:8080/api/cakes/{cakeId}/ratings   { "customerId": "...", "score": 5 }
GET  http://localhost:8080/api/cakes/{cakeId}/ratings/average
```

**Captured:** two ratings submitted for one cake, then the average returned
**`averageRating: 4.5`, `ratingCount: 2`** — a 5 and a 4, meaned and rounded to one decimal place
`HALF_UP`.

A cake nobody has rated returns `200` with `averageRating: null` and `ratingCount: 0`, not a `404`.
Scores outside 1–5 are rejected `400 VALIDATION_ERROR`.

The Rating Service deliberately does **not** call the Catalog Service to check the cake exists. Ratings
are accepted for any UUID. That is a loose-coupling choice: rating throughput does not depend on
catalog availability, and the trade-off is that an orphan rating is possible.

---

## Error handling, observed

One response shape from all five components — `{ code, message, timestamp, path }` — produced by a
`@RestControllerAdvice` in each service and by an `ErrorWebExceptionHandler` at `@Order(-1)` in the
gateway, which runs on WebFlux where controller advice never sees the error.

Every code in this table was returned during the run:

| Code | Status | Triggered by |
|---|---|---|
| `VALIDATION_ERROR` | 400 | `quantity: 0`, missing `cakeId`, malformed UUID, score out of range |
| `INVALID_PRICE_RANGE` | 400 | `minPrice=50&maxPrice=10` |
| `BASKET_EMPTY` | 400 | checkout with nothing in the basket |
| `CAKE_NOT_FOUND` | 404 | basket add for an unknown cake id |
| `CAKE_UNAVAILABLE` | 409 | basket add for `...000000006`, `available: false` |
| `ROUTE_NOT_FOUND` | 404 | a path matching no gateway route |

`CATALOG_UNAVAILABLE` (503) and `SERVICE_UNAVAILABLE` (503) are implemented and documented but were
**not** provoked in this run — doing so means killing a service mid-request.

---

## Bugs this demonstration found

Running the flow was not a formality. Four defects surfaced and were fixed:

1. **`500` where `409`/`404` belonged.** Spring Retry funnels all terminal failures through `@Recover`;
   only the retryable exception had one. Fixed with a `@Recover` that rethrows non-retryable
   exceptions unchanged.
2. **`403` with an empty body on every browser write.** The gateway's `globalcors` allowed ports 3000
   and 30300, not the UI's 8090. Browsers send `Origin` on `POST`/`PUT`/`DELETE` but not on a
   same-origin `GET`, so browsing looked fine and every write failed. Fixed with
   `allowed-origin-patterns: ${CORS_ALLOWED_ORIGIN_PATTERNS:http://localhost:*}`.
3. **Two UI banners permanently visible.** `.basket-total` and `.banner` set `display: flex`, which
   overrides the user-agent `[hidden]` rule. Fixed with `[hidden] { display: none !important; }`.
4. **Kafka client logging drowning the console** under the `local` profile. Muted in the local config.

---

## Reproducing it

Full step-by-step commands are in the root README:

- [Path B — local JVM processes](../README.md#path-b--local-jvm-processes-no-docker) — the path used
  above, and the only one confirmed to work
- [Path A — Docker Compose](../README.md#path-a--docker-compose) — written, never executed
- [Path C — Kubernetes](../README.md#path-c--kubernetes) — written, never executed; see
  [`k8s/README.md`](../k8s/README.md)

Two ways to drive the journey once the stack is up:

- **Web UI** at `http://localhost:8090` — browse, filter, basket, checkout, rating, and the
  notification status, all through the gateway
- **Postman collection** in [`postman/`](../postman) — 8 folders, 31 requests, in journey order; the
  checkout request captures `orderId` into a collection variable so the notification and confirmation
  requests need no copy-paste

Endpoint-level reference: [`api.md`](api.md).
