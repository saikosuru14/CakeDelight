# Cake Delight API Reference

Reference for every HTTP endpoint exposed by the implementation. Describes the code as built, not the
design plan; divergences from `design.md` are listed at the end.

Clients talk to the API Gateway only (Requirement 9.1). Direct service ports are listed so the
services can be exercised in isolation during development.

| Component | Port | Base paths |
|---|---|---|
| API Gateway | 8080 | `/api/**` |
| Catalog Service | 8081 | `/api/cakes` |
| Order Service | 8082 | `/api/baskets`, `/api/orders` |
| Rating Service | 8083 | `/api/cakes/{cakeId}/ratings` |
| Notification Service | 8084 | `/api/notifications/orders/{orderId}` |

All request and response bodies are `application/json`. Identifiers are UUIDs except `customerId`,
which is a client-supplied string. Money is `BigDecimal` scale 2. Timestamps are ISO-8601 instants.

## Gateway route table

Declared in `api-gateway/src/main/resources/application.yml`. Every `uri` comes from an environment
variable; no hostname is baked into the image.

| `order` | Route id | Predicate | Target | Env var | Filters |
|---|---|---|---|---|---|
| 0 | `rating-service-ratings` | `Path=/api/cakes/*/ratings/**` | Rating Service | `RATING_SERVICE_URL` | `Retry` (GET only) |
| 1 | `catalog-service-cakes` | `Path=/api/cakes/**` | Catalog Service | `CATALOG_SERVICE_URL` | `Retry` (GET only) |
| 2 | `order-service-baskets-orders` | `Path=/api/baskets/**,/api/orders/**` | Order Service | `ORDER_SERVICE_URL` | `Retry` (GET only) |
| 3 | `notification-service-notifications` | `Path=/api/notifications/**` | Notification Service | `NOTIFICATION_SERVICE_URL` | `Retry` (GET only) |

Two properties of this table are load-bearing:

- **Route order matters.** The Catalog Service and the Rating Service both serve paths under
  `/api/cakes`. `/api/cakes/*/ratings/**` is both declared first and given the lower `order` value,
  so the more specific ratings route wins and `/api/cakes/**` never swallows a ratings request
  (Requirement 9.2). Reordering the list, or removing the explicit `order` values, sends rating
  traffic to the Catalog Service.
- **No `StripPrefix` filter is applied on any route.** Paths are forwarded unchanged, so the path a
  downstream service sees is byte-for-byte the path the client sent. `GET /api/cakes/{id}` through
  the gateway reaches the Catalog Service as `GET /api/cakes/{id}`.

`/api/cakes/{cakeId}/ratings/average` matches route 0 (`/api/cakes/*/ratings/**`), so it is served by
the Rating Service like the other two ratings endpoints.

## Retry behaviour a caller can observe

Two retry layers sit between a client and a flaky downstream. Both are bounded, and both retry only
failures that could plausibly succeed on a second attempt. Neither changes any status code or response
body: retry alters *when* a caller sees an answer, never *what* the answer is.

### Gateway retry (all four routes, GET only)

| Setting | Value |
|---|---|
| Retries | 2 (so up to 3 attempts) |
| Retried statuses | `series: SERVER_ERROR`, i.e. 5xx only |
| Retried methods | `GET` only |
| Backoff | 50 ms then 100 ms, `factor: 2` |

**GET only is the important part.** The gateway cannot tell whether a downstream already applied a
request before failing, so replaying a state-changing method could duplicate the effect. Concretely,
these are never retried:

| Request | Why retrying it is unsafe |
|---|---|
| `POST /api/orders` | Could create two orders for one checkout |
| `POST /api/baskets/{customerId}/items` | Could add the same cake twice |
| `PUT /api/baskets/{customerId}/items/{cakeId}` | Racy re-application of a quantity change |
| `DELETE /api/baskets/{customerId}/items/{cakeId}` | Could remove an item added after the first attempt |
| `POST /api/cakes/{cakeId}/ratings` | Could record the same rating twice |

A 4xx is never retried on any method: it is a definite answer about the request itself. Note that
Spring Cloud Gateway's `Retry` filter defaults to GET **and POST** when `methods` is omitted, so the
explicit `methods: GET` on every route is what makes the guarantee above hold.

What a caller observes: a GET that hits one failing downstream instance may take up to ~150 ms longer
than usual and still return 200. A GET that fails all three attempts returns the same 5xx it would have
returned without retries.

### Order Service to Catalog Service retry

The only synchronous cross-service call, `GET /api/cakes/{cakeId}` issued during a basket add. On top
of the 5 s connect and 5 s read timeouts, `CatalogClient` retries **only transient failures**: connect
failure, read timeout, and 5xx responses. Up to 3 attempts, exponential backoff of 200 ms then 400 ms
(multiplier 2, capped at 1 s), so at most ~600 ms of added delay. Configured under
`catalog.service.retry.*` in `order-service/src/main/resources/application.yml`.

Two outcomes are deliberately **never** retried, because they are deterministic answers from a healthy
catalog and a second attempt would return the same thing:

| Catalog outcome | Client result | Retried? |
|---|---|---|
| 404 | `404 CAKE_NOT_FOUND` | No — the cake does not exist |
| 200 with `available == false` | `409 CAKE_UNAVAILABLE` | No — availability is a fact, not a fault |
| Connect failure or read timeout | `503 CATALOG_UNAVAILABLE` after 3 attempts | Yes |
| 5xx, or an unreadable/empty body | `503 CATALOG_UNAVAILABLE` after 3 attempts | Yes |

What a caller observes: a basket add against a briefly flaky catalog succeeds with `201` instead of
failing with `503`. A basket add against a catalog that is genuinely down still returns
`503 CATALOG_UNAVAILABLE`, just up to ~600 ms later. `404` and `409` come back as fast as they always
did. Each failed attempt logs at WARN with the cake id and attempt number; exhaustion logs at ERROR.

### Notification delivery retry

Not observable over HTTP — it happens on the Kafka listener thread — but it changes what
`GET /api/notifications/orders/{orderId}` eventually shows. A transient channel failure is retried once
(2 attempts, 200 ms backoff, configured under `notification.delivery.retry.*`), so a momentary delivery
problem can still end as a `SENT` record. A permanent rejection, meaning an event with no
`customerEmail`, is never retried and is recorded `FAILED` immediately. Exhausting the budget produces
the same `FAILED` record plus ERROR log that existed before retries.

## Error response

Every component — all four services and the gateway — returns the same four-field shape on every
error status (Requirement 12.2).

```json
{
  "code": "CAKE_NOT_FOUND",
  "message": "Cake 7f1c8a52-0f6b-4a3f-9c4e-6f9e2b1d0a11 was not found",
  "timestamp": "2025-05-04T10:15:30.123456Z",
  "path": "/api/cakes/7f1c8a52-0f6b-4a3f-9c4e-6f9e2b1d0a11"
}
```

| Field | Type | Notes |
|---|---|---|
| `code` | string | Stable machine-readable code from the table below |
| `message` | string | Human-readable text; names the offending field or carries the identifier |
| `timestamp` | instant | When the error body was rendered |
| `path` | string | Request URI that failed |

Each component keeps its own copy of the `ErrorResponse` record; there is no shared library.

### Error codes

| Code | Status | Produced by | Trigger |
|---|---|---|---|
| `VALIDATION_ERROR` | 400 | catalog, order, rating, notification | Body validation (`MethodArgumentNotValidException`), parameter constraint violation (`ConstraintViolationException`), or a path/query value that will not convert to the declared type (`MethodArgumentTypeMismatchException`, e.g. a malformed UUID or non-numeric `minPrice`) |
| `INVALID_PRICE_RANGE` | 400 | catalog | `minPrice > maxPrice`; the message names both parameters and their values |
| `CAKE_NOT_FOUND` | 404 | catalog, order | Cake identifier not stored (catalog), or the Catalog Service answered 404 for a basket add (order) |
| `BASKET_ITEM_NOT_FOUND` | 404 | order | Update or remove targeting a cake identifier absent from the basket |
| `ORDER_NOT_FOUND` | 404 | order | Order identifier not stored, on the order read or the confirmation call |
| `BASKET_EMPTY` | 400 | order | Checkout for a customer whose basket holds no item; no order is created |
| `CAKE_UNAVAILABLE` | 409 | order | Cake exists but `available == false` |
| `CATALOG_UNAVAILABLE` | 503 | order | Catalog Service refused the connection or exceeded the 5 s connect/read timeout, and the bounded retry budget described above was exhausted |
| `INTERNAL_ERROR` | 500 | catalog, order, rating, notification | Unhandled exception; the stack trace is logged at ERROR and the body carries a generic message |
| `ROUTE_NOT_FOUND` | 404 | gateway | Request path matches no configured route |
| `SERVICE_UNAVAILABLE` | 503 | gateway | `ConnectException` or `TimeoutException` anywhere in the cause chain; the message names the target service resolved from the matched route id, or "The target service" when no route matched |
| `GATEWAY_ERROR` | 500 | gateway | Any other failure while routing |

The gateway handler is an `ErrorWebExceptionHandler` at `@Order(-1)`, not a `@RestControllerAdvice`;
the gateway runs on WebFlux, so errors never reach controller advice.

## Catalog Service (8081)

### `GET /api/cakes`

Lists cakes matching every supplied filter, ANDed.

| Query param | Type | Default | Validation |
|---|---|---|---|
| `name` | string | none | Case-insensitive substring match; blank is treated as absent |
| `category` | string | none | Case-insensitive equality; blank is treated as absent |
| `minPrice` | decimal | none | `@PositiveOrZero`; inclusive lower bound |
| `maxPrice` | decimal | none | `@PositiveOrZero`; inclusive upper bound |
| `page` | integer | 0 | Not rejected when out of range: `null` or `< 0` resolves to 0 |
| `size` | integer | 20 | Not rejected when out of range: `null` or `< 1` resolves to 20 |

Response `PageResponse<CakeResponse>`:

| Field | Type | Notes |
|---|---|---|
| `content` | `CakeResponse[]` | Empty array when nothing matches |
| `page` | int | Applied page number |
| `size` | int | Applied page size |
| `totalElements` | long | Total across all pages; `0` when nothing matches |

Statuses: `200`, `400` (`VALIDATION_ERROR` for a negative or non-numeric price, `INVALID_PRICE_RANGE`
for `minPrice > maxPrice`).

### `GET /api/cakes/{cakeId}`

Path: `cakeId` UUID. Response `CakeResponse`. Statuses: `200`, `400` (malformed UUID), `404`
(`CAKE_NOT_FOUND`).

`CakeResponse`:

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | |
| `name` | string | |
| `description` | string | Nullable |
| `category` | string | |
| `price` | decimal | Scale 2 |
| `available` | boolean | |
| `imageUrl` | string | Nullable |

```json
{
  "content": [
    {
      "id": "7f1c8a52-0f6b-4a3f-9c4e-6f9e2b1d0a11",
      "name": "Classic Chocolate Fudge",
      "description": "Dark chocolate sponge with fudge frosting",
      "category": "birthday",
      "price": 24.50,
      "available": true,
      "imageUrl": "https://cdn.cakedelight.example/chocolate-fudge.jpg"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1
}
```

## Order Service (8082)

Basket writes read the cake from the Catalog Service first (`GET /api/cakes/{cakeId}` through
`CatalogClient`, 5 s connect and read timeouts). Nothing is written before that read succeeds, so
every `404`, `409`, and `503` on the add path leaves the basket unchanged.

### `POST /api/baskets/{customerId}/items`

Request `AddBasketItemRequest`:

| Field | Type | Validation |
|---|---|---|
| `cakeId` | UUID | `@NotNull` — "cakeId is required" |
| `quantity` | integer | `@NotNull` + `@Positive` — "quantity must be a positive integer" |

Response `BasketResponse`. Status is chosen per request: `201` when a new basket line was inserted,
`200` when the line already existed and its quantity was increased. The unit price and cake name are
captured from the Catalog Service on insert and are not re-read on an increment.

Statuses: `201`, `200`, `400` (`VALIDATION_ERROR`), `404` (`CAKE_NOT_FOUND`), `409`
(`CAKE_UNAVAILABLE`), `503` (`CATALOG_UNAVAILABLE`).

### `GET /api/baskets/{customerId}`

Response `BasketResponse`. Always `200`: a customer with no stored items gets an empty `items` array
and `basketTotal` `0.00`, never a `404`.

### `PUT /api/baskets/{customerId}/items/{cakeId}`

Request `UpdateBasketItemRequest`: `{ "quantity": <integer> }`, `@NotNull` + `@Positive`. Replaces
the stored quantity. Response `BasketResponse`. Statuses: `200`, `400`, `404`
(`BASKET_ITEM_NOT_FOUND`).

### `DELETE /api/baskets/{customerId}/items/{cakeId}`

Deletes the line and returns the recalculated basket, so this is a `200` with a body rather than a
`204`. Response `BasketResponse`. Statuses: `200`, `404` (`BASKET_ITEM_NOT_FOUND`).

`BasketResponse`:

| Field | Type | Notes |
|---|---|---|
| `customerId` | string | |
| `items[].cakeId` | UUID | |
| `items[].cakeName` | string | Snapshot captured when the line was created |
| `items[].unitPrice` | decimal | Snapshot captured when the line was created, scale 2 |
| `items[].quantity` | int | Always positive |
| `items[].lineTotal` | decimal | `unitPrice * quantity`, scale 2 HALF_UP |
| `basketTotal` | decimal | Sum of the per-line rounded totals, scale 2 |

```json
{
  "customerId": "cust-1042",
  "items": [
    {
      "cakeId": "7f1c8a52-0f6b-4a3f-9c4e-6f9e2b1d0a11",
      "cakeName": "Classic Chocolate Fudge",
      "unitPrice": 24.50,
      "quantity": 2,
      "lineTotal": 49.00
    }
  ],
  "basketTotal": 49.00
}
```

### `POST /api/orders`

Checkout. Request `CheckoutRequest`:

| Field | Type | Validation |
|---|---|---|
| `customerId` | string | `@NotBlank` — "customerId is required" |
| `customerEmail` | string | `@NotBlank` + `@Email` — "customerEmail must be a well-formed email address" |

No item list is sent: the order is built from the basket already stored for `customerId`. The order
is inserted with `total = basketTotal` and `status = CREATED`, every basket line is copied into
`order_items`, and the basket is cleared in the same transaction. The `order.completed` event is
published after that transaction commits, so a broker failure does not affect this response.

Response `CheckoutResponse`, deliberately narrower than the order read:

| Field | Type | Notes |
|---|---|---|
| `orderId` | UUID | |
| `orderTotal` | decimal | Read from the committed order, scale 2 |
| `status` | enum | `CREATED` |

Statuses: `201`, `400` (`VALIDATION_ERROR` for a missing or malformed email, `BASKET_EMPTY` for an
empty basket).

```json
{ "orderId": "3c2b1a09-8d7e-4f6a-b5c4-d3e2f1a09b8c", "orderTotal": 49.00, "status": "CREATED" }
```

### `GET /api/orders/{orderId}`

Response `OrderResponse`:

| Field | Type | Notes |
|---|---|---|
| `orderId` | UUID | |
| `customerId` | string | |
| `status` | enum | `CREATED` or `CONFIRMED` |
| `orderTotal` | decimal | Committed `orders.total` read as-is, never re-summed from `items` |
| `createdAt` | instant | |
| `items[].cakeId` | UUID | |
| `items[].cakeName` | string | Captured at checkout |
| `items[].unitPrice` | decimal | Captured at checkout, scale 2 |
| `items[].quantity` | int | |
| `items[].lineTotal` | decimal | Derived `unitPrice * quantity` scale 2 HALF_UP; display value only |

`customerEmail` is stored on the order but is **not** exposed here: this read is unauthenticated, and
the address is only needed by the event on its way to the Notification Service.

Statuses: `200`, `400` (malformed UUID), `404` (`ORDER_NOT_FOUND`).

### `POST /api/orders/{orderId}/confirmation`

Moves the order to `CONFIRMED`. Modelled as a POST to a sub-resource, so the client never sends a
status value and the only reachable transition stays `CREATED -> CONFIRMED`. Idempotent: confirming
an already-`CONFIRMED` order answers `200` with `CONFIRMED` rather than a conflict.

No request body. Response `OrderStatusResponse`: `{ orderId, status }`. Statuses: `200`, `400`
(malformed UUID), `404` (`ORDER_NOT_FOUND`).

## Rating Service (8083)

All three endpoints hang off `/api/cakes/{cakeId}/ratings`. The Rating Service does not validate
`cakeId` against the Catalog Service; a rating can be stored for any UUID.

### `POST /api/cakes/{cakeId}/ratings`

Request `RatingRequest`:

| Field | Type | Validation |
|---|---|---|
| `customerId` | string | `@NotNull` — "customerId is required". Note: `@NotNull`, not `@NotBlank`, so an empty string is accepted |
| `score` | integer | `@NotNull` + `@Min(1)` + `@Max(5)` — "score must be between 1 and 5" |

The rating identifier and `createdAt` are generated by the service; the client never supplies them.
Response `RatingResponse`. Statuses: `201`, `400` (`VALIDATION_ERROR`).

### `GET /api/cakes/{cakeId}/ratings`

Response `RatingResponse[]`; empty array when the cake has no rating. Statuses: `200`, `400`
(malformed UUID).

`RatingResponse`: `{ id: UUID, cakeId: UUID, customerId: string, score: int, createdAt: instant }`.

### `GET /api/cakes/{cakeId}/ratings/average`

Response `AverageRatingResponse`:

| Field | Type | Notes |
|---|---|---|
| `cakeId` | UUID | Echoed from the path |
| `averageRating` | decimal | Arithmetic mean rounded to **one** decimal place HALF_UP; `null` when there is no rating |
| `ratingCount` | long | `0` when there is no rating |

A cake with no rating is a `200` with `averageRating: null`, not a `404`. Statuses: `200`, `400`
(malformed UUID).

```json
{ "cakeId": "7f1c8a52-0f6b-4a3f-9c4e-6f9e2b1d0a11", "averageRating": 4.3, "ratingCount": 7 }
```

## Notification Service (8084)

### `GET /api/notifications/orders/{orderId}`

Lists every stored delivery attempt for the order. Always a list, never a single record, because
`FAILED` attempts accumulate while at most one `SENT` record can exist. An order with no attempt
answers `200` with an empty array, not `404`.

Response `NotificationResponse[]`:

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | Identifier of the attempt record |
| `orderId` | UUID | |
| `channel` | string | `EMAIL` or `IN_APP`, whichever `cakedelight.notification.channel` selects. Exactly one channel is active at a time; there is no fan-out, so all records for one run carry the same value. Defaults to `EMAIL` |
| `status` | enum | `SENT` or `FAILED` |
| `attemptedAt` | instant | |

Statuses: `200`, `400` (malformed UUID).

```json
[
  {
    "id": "b71e4c39-2a5d-4e18-9f0b-77c6d4a2e510",
    "orderId": "3c2b1a09-8d7e-4f6a-b5c4-d3e2f1a09b8c",
    "channel": "EMAIL",
    "status": "SENT",
    "attemptedAt": "2025-05-04T10:15:31.442Z"
  }
]
```

This service exposes no write endpoint. Notification records are created only by the
`order.completed` listener; see [`event-contract.md`](event-contract.md).

## Operational endpoints

Exposed by all five components, not routed through the gateway.

| Path | Purpose |
|---|---|
| `/actuator/health` | Overall health. `show-details: always` on the gateway, `never` on the four services |
| `/actuator/health/liveness` | `livenessState` |
| `/actuator/health/readiness` | `readinessState`, plus `db` on the four services |
| `/actuator/info` | Build info |
| `/swagger-ui.html` | Swagger UI |
| `/v3/api-docs` | OpenAPI document |

## Divergences from design.md

| Area | design.md | Implementation |
|---|---|---|
| Catalog `page` / `size` | Declared as request-param defaults 0 and 20 | Declared `required = false` with no default; `CakeService` resolves `null`, `page < 0`, and `size < 1` to 0 and 20. Out-of-range paging is silently clamped, not a `400` |
| Basket add statuses | `201`, `200`, `400`, `404`, `409` | Also `503` `CATALOG_UNAVAILABLE` when the Catalog Service is unreachable |
| Gateway error codes | `ROUTE_NOT_FOUND`, `SERVICE_UNAVAILABLE` | Adds `GATEWAY_ERROR` (500) as the fallback for any other routing failure |
| Catalog client method | `getCake(UUID)` | `CatalogClient.fetchAvailableCake(UUID)` — the availability check is part of the fetch |
| Route precedence | Relies on declaration order | Declaration order **and** explicit `order: 0..3` values |
| `RatingRequest.customerId` | "`customerId` with `@NotNull`" | `@NotNull` as designed, which means a blank string passes validation |
