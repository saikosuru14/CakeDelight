# Cake Delight

Cloud-native microservices application delivering one end-to-end customer journey: browse cakes,
filter them, manage a basket, check out, receive an order confirmation, and rate a cake.

Five independently deployable components, one database per owning service, and Kafka carrying the
`order.completed` event from the Order Service to the Notification Service.

| Component | Port | Owns | Database |
|---|---|---|---|
| `api-gateway` | 8080 | Routing config | — |
| `catalog-service` | 8081 | Cakes | `catalog_db` |
| `order-service` | 8082 | Baskets, Orders | `order_db` |
| `rating-service` | 8083 | Ratings | `rating_db` |
| `notification-service` | 8084 | Notification records | `notification_db` |
| `web-ui` | 8090 | Nothing | — |

The gateway on port 8080 is the only service component exposed to clients. Every example below goes
through it, and so does the [web UI](#web-ui).

Reference docs:

- [`docs/OVERVIEW.md`](docs/OVERVIEW.md) — **start here.** High-level overview: how the pieces fit
  together, what each service does, the database tables, the tech stack, and the dependencies
- [`docs/api.md`](docs/api.md) — endpoint reference for all four services plus the gateway route table
- [`docs/event-contract.md`](docs/event-contract.md) — `order.completed` payload contract
- [`docs/DEMO.md`](docs/DEMO.md) — end-to-end demonstration, with the status codes, order id, and
  totals actually captured on a live run
- [`docs/data-model.md`](docs/data-model.md) — the four schemas, column by column, and why only one
  foreign key exists in the whole system
- [`docs/capstone-traceability.md`](docs/capstone-traceability.md) — every item in the capstone brief
  mapped to the code that satisfies it, and an honest list of what is unverified
- [`k8s/README.md`](k8s/README.md) — apply procedure, secret handling, the scaling story, and known
  gaps in the manifests

---

## How it works

### The shape of the system

The gateway is the only client entry point. It is Spring Cloud Gateway on WebFlux, and it holds a
route table and nothing else — no database, no business logic.

| `order` | Predicate | Target |
|---|---|---|
| 0 | `/api/cakes/*/ratings/**` | `rating-service` |
| 1 | `/api/cakes/**` | `catalog-service` |
| 2 | `/api/baskets/**`, `/api/orders/**` | `order-service` |
| 3 | `/api/notifications/**` | `notification-service` |

Two properties of that table carry weight. The ratings route must come before `/api/cakes/**`, which
is why the `order` values are explicit: reverse them and every rating request lands on the Catalog
Service. And no route applies a `StripPrefix` filter, so a downstream service sees byte-for-byte the
path the browser sent — `GET /api/cakes/{id}` arrives at the Catalog Service as `GET /api/cakes/{id}`.

Four services, one database each. No shared schema and no cross-database joins. `order_items.cake_id`
and `notifications.order_id` are plain UUIDs with no foreign key precisely because the data they point
at lives in another service's database.

### Two ways the services talk

**Synchronous, one call only.** The Order Service reads cake price and availability from the Catalog
Service over REST through `CatalogClient`: 5 s connect timeout, 5 s read timeout, Spring Retry with 3
attempts and exponential backoff. It retries `CatalogUnavailableException` and nothing else — a `404`
or a cake marked unavailable is a definite answer from a healthy catalog, so replaying the call would
return the same thing.

**Asynchronous, one topic only.** The Order Service publishes `order.completed` to Kafka *after* the
checkout transaction commits, via `@TransactionalEventListener(phase = AFTER_COMMIT)`. A rolled-back
checkout therefore publishes nothing. The Notification Service consumes the topic in the
`notification-service` consumer group. If the broker is unreachable the failure is logged at ERROR and
checkout still returns `201`: the order is never lost because its event could not be sent.

There is no shared library. Publisher and consumer each keep their own copy of the event record, which
is why [`docs/event-contract.md`](docs/event-contract.md) is the contract rather than a Java type.

### The journey, step by step

| # | Step | Call | Component | What is persisted |
|---|---|---|---|---|
| 1 | Browse | `GET /api/cakes` | catalog | nothing |
| 2 | Filter | `GET /api/cakes?name=&category=&minPrice=&maxPrice=` | catalog | nothing |
| 3 | Add to basket | `POST /api/baskets/{customerId}/items` | order → catalog | basket line |
| 4 | View / update / remove | `GET`, `PUT`, `DELETE` on the basket | order | basket line |
| 5 | Checkout | `POST /api/orders` | order → Kafka | order + order items, basket cleared |
| 6 | Notification | `GET /api/notifications/orders/{orderId}` | notification ← Kafka | one row per attempt |
| 7 | Rate | `POST /api/cakes/{cakeId}/ratings` | rating | rating |

**1. Browse.** `GET /api/cakes` reaches the Catalog Service and returns a page — defaults page 0,
size 20 — over 24 seeded cakes across 9 categories.

**2. Filter.** Same endpoint. `name` is a case-insensitive substring, `category` is case-insensitive
equality, `minPrice` and `maxPrice` are inclusive bounds. All four are ANDed inside one null-tolerant
query, so any combination works and an omitted parameter simply drops out.

**3. Add to basket.** The Order Service reads the cake from the Catalog Service **first**, then
writes. `201` for a new basket line, `200` when the line already existed and its quantity was
incremented. `cakeName` and `unitPrice` are snapshotted onto the line, so a later catalog price change
cannot move an existing basket. Because nothing is written until the catalog read succeeds, `404`
`CAKE_NOT_FOUND`, `409` `CAKE_UNAVAILABLE`, and `503` `CATALOG_UNAVAILABLE` all leave the basket
untouched.

**4. View, update, remove.** Every one of these responses returns the whole basket with `basketTotal`
recomputed as the sum of the per-line `unitPrice * quantity`, rounded to 2 decimal places `HALF_UP`.
That arithmetic lives in exactly one place, `BasketService`, so the total cannot disagree with itself
between endpoints.

**5. Checkout.** `POST /api/orders` carries only `customerId` and `customerEmail`. There is no item
list, so a client cannot supply its own prices or quantities. One transaction inserts the order,
copies the basket lines into `order_items`, and clears the basket. The event publishes after that
transaction commits.

**6. Notification.** The Notification Service consumes `order.completed`, composes the confirmation,
and records one row per attempt with status `SENT` or `FAILED`. Idempotency has two layers: a pre-send
check for an existing `SENT` row, and the partial unique index `uq_notifications_order_sent`. Read the
result back with `GET /api/notifications/orders/{orderId}`. This step is asynchronous, so a read fired
immediately after checkout can legitimately return an empty array — retry it.

**7. Rate.** `POST /api/cakes/{cakeId}/ratings` with a score of 1–5, then
`GET /api/cakes/{cakeId}/ratings/average`, which rounds to one decimal place `HALF_UP` and returns a
`null` average with `ratingCount` 0 for a cake nobody has rated.

### One error shape everywhere

All five components — four services and the gateway — return the same body on every error status:

```json
{ "code": "CAKE_NOT_FOUND", "message": "...", "timestamp": "...", "path": "/api/cakes/..." }
```

The full list of codes, the status each maps to, and the component that produces it are in the
[error codes table in `docs/api.md`](docs/api.md#error-codes).

---

## Prerequisites

| Tool | Version | Needed for |
|---|---|---|
| JDK | 21 | building and running any service — all three paths |
| Maven | 3.9+ | `mvn clean package`, `mvn test` — all three paths |
| Node.js | 20+ | `web-ui/dev-server.js`, the UI in [path B](#path-b--local-jvm-processes-no-docker) |
| Apache Kafka | 3.7.x | the broker in [path B](#path-b--local-jvm-processes-no-docker) |
| Docker + Compose v2 | recent | [path A](#path-a--docker-compose), Testcontainers integration tests |
| kubectl | 1.28+ | [path C](#path-c--kubernetes) |
| Kubernetes cluster | any | kind, minikube, Docker Desktop, or a real cluster |
| curl | any | the walkthrough below |

`JAVA_HOME` must point at a JDK 21 installation and `mvn` must be on `PATH`. If `mvn` is not
recognised, install Maven and add its `bin` directory to `PATH`; if the build reports a wrong class
file version, `JAVA_HOME` is pointing at an older JDK.

---

## Running it

Three paths, in order of intent:

| Path | What it uses | Status |
|---|---|---|
| [A — Docker Compose](#path-a--docker-compose) | containers, PostgreSQL 16, Kafka | supported path, **not yet executed** |
| [B — Local JVM processes](#path-b--local-jvm-processes-no-docker) | `java -jar`, H2 in-memory, local Kafka | **verified working**, full seven-step flow including Kafka |
| [C — Kubernetes](#path-c--kubernetes) | manifests in `k8s/` | written, **never applied** |

### Path A — Docker Compose

```bash
# from the repository root - long running, run this yourself
docker compose up --build
```

That brings up Kafka, four PostgreSQL 16 databases, the four services, the gateway, and the web UI.
The UI is at **http://localhost:8090** and the gateway at **http://localhost:8080**. Compose gates each
service on the readiness probe of its dependencies, so the first start takes a couple of minutes while
images build and Flyway migrates. Once `api-gateway` reports healthy:

```bash
curl http://localhost:8080/actuator/health
```

Tear down, including the database volumes:

```bash
docker compose down -v
```

**Status: not yet executed.** No Docker engine was available in the environment this was developed in,
so `docker-compose.yml` is unvalidated at runtime. It is written against the same images, ports, and
environment variables documented elsewhere in this file, and it is consistent with them, but nothing
here confirms the stack starts. Path B below is the one that has been run end to end.

### Path B — Local JVM processes, no Docker

This is the path that has been run end to end: all seven steps, Kafka included, so the
`order.completed` event really flows and a `SENT` notification record really appears.

Every service runs under the `local` Spring profile, which points it at an H2 in-memory database.
The `local` profile lives in `src/main/resources/application-local.yml` and so is packaged into the fat
jar. That is exactly why it exists: the `test` profile lives in `src/test/resources` and is **not** in
the jar, so it cannot be activated from `java -jar`.

#### Prerequisites for this path

| Requirement | Notes |
|---|---|
| JDK 21, with `JAVA_HOME` pointing at it | any JDK 21 distribution |
| Maven 3.9+ | on `PATH`, or invoked by full path if it is not |
| Node.js 20+ | for the UI dev server |

#### 1. Build all five

Each service is an independent Maven project; there is no parent aggregator, so build them one at a
time. If `mvn` is on `PATH`, this is enough:

```powershell
foreach ($s in 'catalog-service','rating-service','order-service','notification-service','api-gateway') {
  mvn -B -f "$s\pom.xml" clean package
}
```

If Maven is **not** on `PATH`, set the two variables once and invoke the binary by full path:

```powershell
$env:JAVA_HOME = '<path-to-jdk-21>'
$mvn = '<path-to-maven>\bin\mvn.cmd'

foreach ($s in 'catalog-service','rating-service','order-service','notification-service','api-gateway') {
  & $mvn -B -f "$s\pom.xml" clean package
}
```

Each build produces `<service>\target\<service>-1.0.0.jar`.

#### 2. One-time Kafka setup

Kafka runs as a plain single-node KRaft broker, no Docker, no ZooKeeper.

| Item | Location |
|---|---|
| Distribution | Apache Kafka 3.7.1, from the Apache archive, extracted to `%USERPROFILE%\kafka_2.13-3.7.1` |
| KRaft config | `%USERPROFILE%\cd-kraft.properties` |
| Log directory | `%USERPROFILE%\kdata` |
| Launcher | `kafka-run.cmd` at the repository root |

Format the storage directory once, before the first start:

```bat
REM generate a cluster id
java -cp "%USERPROFILE%\kafka_2.13-3.7.1\libs\*" kafka.tools.StorageTool random-uuid

REM format, using that id
java -cp "%USERPROFILE%\kafka_2.13-3.7.1\libs\*" kafka.tools.StorageTool format ^
  -t <clusterId> -c "%USERPROFILE%\cd-kraft.properties"
```

Two Windows-specific problems are worth recording, because both look like broken configuration and
are not:

- **`kafka-storage.bat` and `kafka-server-start.bat` both fail with "The input line is too long".**
  The scripts build a literal classpath by enumerating all 119 jars in `libs\`, and the resulting
  command line exceeds the Windows limit. The fix is to skip the scripts and invoke
  `java -cp "<kafka>\libs\*"` directly, letting the JVM expand the wildcard itself.
- **PowerShell mangles `-D` system-property arguments**, producing
  `Could not find or load main class .configuration=...`. That is why the broker launcher is a `.cmd`
  file run through `cmd` rather than a PowerShell command line.

#### 3. Start in this order

Each command goes in its own terminal and stays in the foreground. Expect **15–70 s** per service
before it reports ready.

**1. Kafka broker — port 9092**

```powershell
cmd /c kafka-run.cmd
```

First, so the topic exists and the consumer joins the group cleanly. Not strictly a hard dependency:
both Kafka clients reconnect on their own if the broker starts later.

**2. `catalog-service` — port 8081**

```powershell
java -jar catalog-service\target\catalog-service-1.0.0.jar --spring.profiles.active=local
```

Before the Order Service, because the Order Service reads cakes from it on every basket add.

**3. `rating-service` — port 8083**

```powershell
java -jar rating-service\target\rating-service-1.0.0.jar --spring.profiles.active=local
```

Independent of everything else; position does not matter.

**4. `order-service` — port 8082**

```powershell
java -jar order-service\target\order-service-1.0.0.jar --spring.profiles.active=local
```

No environment setup needed: the `local` profile defaults `CATALOG_SERVICE_URL` to
`http://localhost:8081` and the Kafka bootstrap servers to `localhost:9092`.

**5. `notification-service` — port 8084**

```powershell
java -jar notification-service\target\notification-service-1.0.0.jar --spring.profiles.active=local
```

Also defaults its Kafka bootstrap servers to `localhost:9092`.

**6. `api-gateway` — port 8080**

The gateway has no database and needs no profile, but all four downstream URLs are mandatory:

```powershell
$env:CATALOG_SERVICE_URL      = 'http://localhost:8081'
$env:ORDER_SERVICE_URL        = 'http://localhost:8082'
$env:RATING_SERVICE_URL       = 'http://localhost:8083'
$env:NOTIFICATION_SERVICE_URL = 'http://localhost:8084'
java -jar api-gateway\target\api-gateway-1.0.0.jar
```

**7. Web UI — port 8090**

```powershell
node web-ui\dev-server.js
```

Serves the static client and reverse-proxies `/api/` to the gateway, so the browser stays same-origin.
Open **http://localhost:8090**.

#### 4. Verify

```powershell
curl http://localhost:8080/actuator/health          # expect status UP

# every port in the stack should be listening
8080,8081,8082,8083,8084,8090,9092 |
  ForEach-Object { "$_ -> " + (Test-NetConnection -ComputerName localhost -Port $_ -InformationLevel Quiet) }
```

Then run the [walkthrough](#end-to-end-walkthrough) — it works unchanged against
`http://localhost:8080`, or drive the same journey from the [web UI](#web-ui).

### Path C — Kubernetes

Manifests exist in `k8s/` and have **never been applied**: no cluster has been reached from this
machine, so they are unvalidated. The committed Secrets carry `REPLACE_ME` placeholders that must be
replaced first, and `kubectl apply -R -f k8s/` is required because plain `-f` is not recursive. Full
instructions are in [Deploying to Kubernetes](#deploying-to-kubernetes), with the longer form and the
known gaps in [`k8s/README.md`](k8s/README.md).

---

## H2 consoles

Under the `local` profile every service exposes the H2 web console at `/h2-console` on its own port.
These are **not** routed through the gateway; open them directly. User `sa`, password blank.

| Service | Console | JDBC URL |
|---|---|---|
| `catalog-service` | http://localhost:8081/h2-console | `jdbc:h2:mem:catalog_db;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH` |
| `order-service` | http://localhost:8082/h2-console | `jdbc:h2:mem:order_db;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1` |
| `rating-service` | http://localhost:8083/h2-console | `jdbc:h2:mem:rating_db;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH` |
| `notification-service` | http://localhost:8084/h2-console | `jdbc:h2:mem:notification_db;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1` |

Paste the URL exactly; the console pre-fills `jdbc:h2:~/test`, which connects to a different, empty
database and shows no tables.

Queries worth running once connected:

| Database | Query |
|---|---|
| `catalog_db` | `SELECT category, COUNT(*) FROM cakes GROUP BY category;` |
| | `SELECT id, name, price, available FROM cakes WHERE available = FALSE;` |
| `order_db` | `SELECT * FROM basket_items;` |
| | `SELECT o.id, o.customer_id, o.total, o.status, COUNT(i.id) FROM orders o LEFT JOIN order_items i ON i.order_id = o.id GROUP BY o.id, o.customer_id, o.total, o.status;` |
| `rating_db` | `SELECT cake_id, AVG(score), COUNT(*) FROM ratings GROUP BY cake_id;` |
| `notification_db` | `SELECT order_id, channel, status, attempted_at FROM notifications ORDER BY attempted_at DESC;` |

Every database also carries `flyway_schema_history`, which is the quickest way to confirm which
migrations ran and that all of them succeeded.

### Everything except the Kafka log is in-memory

Under the `local` profile the data lives inside the JVM. Restarting a service wipes its tables and
replays its migrations, so the catalogue comes back — it is seeded by `V2` and `V3` — while baskets,
orders, ratings, and notification records do not. A restart mid-demo means starting the journey again.

The Kafka log in `%USERPROFILE%\kdata` is on disk and does survive a broker restart.

---

## Web UI

The storefront is at **http://localhost:8090** on every path: served by nginx under Compose, and by
`web-ui/dev-server.js` under [path B](#path-b--local-jvm-processes-no-docker). It is the "client
application or user interface" component of the architecture: plain HTML, CSS, and vanilla JavaScript,
with no data and no business rules of its own.

```
web-ui/
├── index.html
├── styles.css
├── app.js
├── nginx.conf                  # serves the client and proxies /api/ to the gateway
└── Dockerfile                  # nginx:1.27-alpine, no build stage
```

nginx does two jobs: it serves the three static files, and it reverse-proxies `/api/` to
`http://api-gateway:8080` over the compose network. Paths are forwarded unchanged, so the UI calls
exactly the endpoints documented in [`docs/api.md`](docs/api.md).

The gateway allows browser origins under `spring.cloud.gateway.globalcors` in
`api-gateway/src/main/resources/application.yml`. That block is not optional: the gateway rejects any
request carrying an `Origin` header it does not allow with `403` and an empty body, and browsers send
`Origin` on every POST, PUT, and DELETE — so without it add-to-basket, checkout, and rating
submission all fail while reads keep working. `CORS_ALLOWED_ORIGIN_PATTERNS` overrides the default
(comma-separated). The default is `http://localhost:*`, i.e. localhost only, which suits a local demo
and must be narrowed to the exact UI origin for a real deployment.

The customer ID sits in the header, defaults to `cust-001`, and is the only state the client stores
(in `localStorage`), so a page reload keeps the basket context.

Nine capabilities, matching the walkthrough below:

1. Browse cakes — card grid with name, description, category, price, availability, and image
2. Filter by name, category, and price range
3. Add a cake to the basket with a quantity
4. View the basket with line totals and the basket total
5. Update a basket line quantity
6. Remove a basket line
7. Check out with a customer ID and email, showing the created order ID, total, and status
8. View the notification record for that order — proof the `order.completed` event reached the
   Notification Service
9. Submit a 1–5 rating for a cake and see its average rating and rating count

Errors are never silent: the shared `{ code, message, timestamp, path }` body is surfaced in a
dismissible banner, so the interesting failure paths are demonstrable from the UI. Adding the
unavailable seeded cake (Lemon Drizzle Cupcake, marked *Unavailable* on its card) shows
`409 CAKE_UNAVAILABLE`; a min price above the max shows `400 INVALID_PRICE_RANGE`; checking out with
an empty basket shows `400 BASKET_EMPTY`.

The seeded `imageUrl` values point at a placeholder CDN host that does not resolve, so cards show a
labelled fallback tile instead of a photo. That is expected against the demo seed data.

After editing `web-ui/app.js` or `web-ui/styles.css`, hard-refresh the browser with **Ctrl+F5**. The
dev server sends `Cache-Control: no-store`, but an already-open tab keeps the previously parsed
JavaScript in memory, so a normal reload can still run the old code.

### Running the UI without Docker

If the Docker engine is unavailable, `web-ui/dev-server.js` does the same two jobs nginx does using
only Node built-ins: it serves the static files and reverse-proxies `/api/` to the gateway with the
path unchanged. The `Origin` header is forwarded through as-is, so the gateway CORS block above is
what makes writes work in this mode too.

```bash
# from the repository root - long running, run this yourself
node web-ui/dev-server.js
```

Then open **http://localhost:8090**, the same URL as the compose setup. The gateway must already be
running on port 8080 or `/api/` calls come back as `502 GATEWAY_UNREACHABLE`. Override the defaults
with `PORT` and `API_TARGET` if either moves.

`nginx.conf` and the Dockerfile stay the deployment path, and Docker Compose remains the supported way
to run the stack. This dev server is what [path B](#path-b--local-jvm-processes-no-docker) uses, and it
is the only UI setup that has actually been run here.

---

## Building and testing

Each service is a self-contained Maven project. There is no parent aggregator pom, so build them
one at a time from their own directory.

```bash
# build one service (run from catalog-service/, order-service/, rating-service/,
# notification-service/, or api-gateway/)
mvn clean package

# run its tests
mvn test

# run it locally - long running, run this yourself
mvn spring-boot:run
```

Building all five, from the repository root:

```bash
for s in catalog-service rating-service order-service notification-service api-gateway; do
  (cd "$s" && mvn clean package) || break
done
```

```powershell
# PowerShell equivalent
foreach ($s in 'catalog-service','rating-service','order-service','notification-service','api-gateway') {
  Push-Location $s; mvn clean package; Pop-Location
}
```

Both forms assume `mvn` is on `PATH`. If it is not, use the full-path `-f` form in
[path B, step 1](#1-build-all-five).

`mvn spring-boot:run` needs the environment variables from the table below to be set in the shell,
because nothing is defaulted in `application.yml`. Adding
`-Dspring-boot.run.profiles=local` avoids that for the four services, since the `local` profile
supplies its own datasource and defaults; the gateway still needs its four URLs either way.

### Test databases

Unit and web-slice tests need no database. Beyond those, two options exist:

- **H2 `test` profile** — each service ships `src/test/resources/application-test.yml` pointing at an
  in-memory H2 database in `MODE=PostgreSQL`, activated with `@ActiveProfiles("test")`. Flyway runs
  H2-compatible mirrors from `src/test/resources/db/migration-h2/`. Fast, no Docker required. Because
  it lives under `src/test/resources` it is not packaged into the jar, which is what the separate
  `local` profile in `src/main/resources` is for — see
  [path B](#path-b--local-jvm-processes-no-docker).
- **Testcontainers PostgreSQL** — real PostgreSQL 16 and real Kafka, used by the `*IT` integration
  tests. Requires a running Docker daemon.

Two things to know about the H2 profile before relying on it:

1. **H2 cannot express the partial unique index.** The PostgreSQL migration enforces at-most-one
   `SENT` notification per order with `CREATE UNIQUE INDEX uq_notifications_order_sent ON
   notifications (order_id) WHERE status = 'SENT'`. H2 supports neither partial nor expression
   indexes, so the mirror substitutes a plain non-unique index on `(order_id, status)`. The database
   level guarantee behind Requirements 8.4 and 8.6 therefore only exists on PostgreSQL. This applies
   to the `local` profile too: on any H2-backed run, including
   [path B](#path-b--local-jvm-processes-no-docker), only the listener's application-level pre-send
   check protects the invariant. Any test asserting that a duplicate `SENT` row is rejected, or
   exercising a concurrent-listener race, must run against Testcontainers PostgreSQL instead. The
   listener's application-level check behaves the same on both.
2. **The dual migrations are maintained by hand.** `src/main/resources/db/migration/` is the source of
   truth. Every change there has to be mirrored manually into `src/test/resources/db/migration-h2/`.
   Skip the mirror and the `test` profile drifts from production; `ddl-auto: validate` catches column
   drift but not index or constraint drift.

---

## Configuration

All configuration comes from environment variables. No credentials live in `application.yml`, the
Dockerfiles, or committed manifests.

| Component | Variable | Example |
|---|---|---|
| `api-gateway` | `CATALOG_SERVICE_URL` | `http://catalog-service:8081` |
| | `ORDER_SERVICE_URL` | `http://order-service:8082` |
| | `RATING_SERVICE_URL` | `http://rating-service:8083` |
| | `NOTIFICATION_SERVICE_URL` | `http://notification-service:8084` |
| `catalog-service` | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://catalog-db:5432/catalog_db` |
| | `SPRING_DATASOURCE_USERNAME` | `cakedelight` |
| | `SPRING_DATASOURCE_PASSWORD` | *(from a Secret)* |
| `rating-service` | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://rating-db:5432/rating_db` |
| | `SPRING_DATASOURCE_USERNAME` | `cakedelight` |
| | `SPRING_DATASOURCE_PASSWORD` | *(from a Secret)* |
| `order-service` | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://order-db:5432/order_db` |
| | `SPRING_DATASOURCE_USERNAME` | `cakedelight` |
| | `SPRING_DATASOURCE_PASSWORD` | *(from a Secret)* |
| | `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `kafka:9092` |
| | `CATALOG_SERVICE_URL` | `http://catalog-service:8081` |
| `notification-service` | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://notification-db:5432/notification_db` |
| | `SPRING_DATASOURCE_USERNAME` | `cakedelight` |
| | `SPRING_DATASOURCE_PASSWORD` | *(from a Secret)* |
| | `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `kafka:9092` |

Every variable is mandatory under the default profile. A missing one fails startup with an unresolved
placeholder error rather than falling back to a default.

The `local` profile is the exception, and only for the four services: it inlines the H2 datasource and
defaults `CATALOG_SERVICE_URL` to `http://localhost:8081` and the Kafka bootstrap servers to
`localhost:9092`, so no environment setup is needed. The gateway has no `local` profile, so its four
`*_SERVICE_URL` variables must be set on every path — see
[path B, step 6](#path-b--local-jvm-processes-no-docker).

Pointing a service at a database owned by another service is invalid configuration: Flyway runs with
`baseline-on-migrate: false` and `validate-on-migrate: true`, so startup fails with a logged
migration-checksum error instead of writing into a foreign schema.

---

## Deploying to Kubernetes

Manifests live in `k8s/`, grouped by component. All resources are namespaced to `cake-delight`.

These manifests have never been applied to a cluster, so they are unvalidated at runtime. Treat the
steps below as the intended procedure rather than a recorded one.

[`k8s/README.md`](k8s/README.md) is the fuller version of this section: the complete DNS table, the
image-loading step, which components have an HPA and why the databases and Kafka do not, and the known
gaps. What follows here is the short path.

### 0. Load the images

Nothing is pushed to a registry and every Deployment sets `imagePullPolicy: IfNotPresent`, so build
first and then load each image onto the node, or the pods sit in `ErrImageNeverPull`:

```bash
docker compose build
for c in catalog-service order-service rating-service notification-service api-gateway web-ui; do
  minikube image load cake-delight/$c:1.0.0
done
```

### 1. Replace the placeholder secrets

Every committed `secret.yaml` carries `REPLACE_ME` values. They are placeholders, not credentials,
and must be replaced before anything is deployed — a database pod started with `POSTGRES_PASSWORD:
REPLACE_ME` will happily accept `REPLACE_ME` as its password.

Generate real values without writing them to a file:

```bash
kubectl -n cake-delight create secret generic catalog-db-credentials \
  --from-literal=POSTGRES_USER=cakedelight \
  --from-literal=POSTGRES_PASSWORD="$(openssl rand -base64 24)" \
  --dry-run=client -o yaml | kubectl apply -f -
```

Repeat for `order-db-credentials`, `rating-db-credentials`, and `notification-db-credentials`, and
for the per-service application Secrets, which need the matching `SPRING_DATASOURCE_USERNAME` and
`SPRING_DATASOURCE_PASSWORD` values.

### 2. Apply the manifests

```bash
kubectl apply -f k8s/namespace.yaml
kubectl apply -R -f k8s/
```

Both commands matter:

- **The namespace goes first.** Every other manifest declares `namespace: cake-delight`. Applying
  them before the namespace exists fails with `namespaces "cake-delight" not found`, because
  `kubectl apply` does not order resources by dependency.
- **`-R` is required.** `kubectl apply -f k8s/` is not recursive: it only picks up files directly
  inside `k8s/`, which is just `namespace.yaml`. Everything else lives in `k8s/postgres/`,
  `k8s/kafka/`, `k8s/catalog-service/`, and so on, and is skipped without `-R`.

### 3. Verify

```bash
kubectl -n cake-delight get pods
kubectl -n cake-delight rollout status deployment/api-gateway
```

Databases and Kafka come up first; the services stay unready until their readiness probes pass,
which is after Flyway has migrated.

### 4. Reach the gateway and the UI

Two Services are `NodePort` — `api-gateway` on 30080 and `web-ui` on 30090. Everything else is
`ClusterIP`, so 8081–8084, 5432, and 9092 stay inside the cluster. For a local cluster, port
forwarding is usually simpler:

```bash
# both long running, run these yourself
kubectl -n cake-delight port-forward svc/api-gateway 8080:8080
kubectl -n cake-delight port-forward svc/web-ui 8090:80
```

The walkthrough below then works unchanged against `http://localhost:8080`, and the storefront is at
`http://localhost:8090` as usual.

The UI pod runs the same image as Compose but with its nginx server block replaced from
`k8s/web-ui/configmap.yaml`. The baked-in `web-ui/nginx.conf` sets `resolver 127.0.0.11`, which is
Docker's embedded DNS and does not exist in a pod, so leaving it in place would break every `/api/`
call in the cluster.

---

## End-to-end walkthrough

Everything goes through the gateway on `http://localhost:8080`. Ports 8081–8084 are not published in
Compose and are `ClusterIP`-only in Kubernetes. Under
[path B](#path-b--local-jvm-processes-no-docker) they are plain local ports and reachable directly,
which is handy for the [H2 consoles](#h2-consoles) and per-service Swagger UIs but is not how a client
is meant to talk to the stack.

The examples use a seeded cake with a fixed identifier from
`catalog-service/src/main/resources/db/migration/V2__seed_cakes.sql`, so they work against a freshly
started stack:

```bash
CAKE_ID=11111111-1111-4111-8111-000000000001   # Chocolate Truffle, 23.75, Birthday
CUSTOMER=cust-001
```

### 1. Browse the catalog

```bash
curl -s "http://localhost:8080/api/cakes"
```

```json
{
  "content": [
    {
      "id": "11111111-1111-4111-8111-000000000001",
      "name": "Chocolate Truffle",
      "description": "Dark chocolate sponge layered with truffle ganache and cocoa nibs.",
      "category": "Birthday",
      "price": 23.75,
      "available": true,
      "imageUrl": "https://cdn.cakedelight.example/cakes/chocolate-truffle.jpg"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 24
}
```

Omitted paging parameters default to page 0, size 20. The seed data is 24 cakes across 9 categories,
so a default request returns the first 20 of 24.

### 2. Filter

Filters combine with AND. Name matching is a case-insensitive substring, category is case-insensitive
equality, and the price bounds are inclusive.

```bash
curl -s "http://localhost:8080/api/cakes?category=cupcake&minPrice=3.00&maxPrice=4.50"
curl -s "http://localhost:8080/api/cakes?name=cheesecake"
curl -s "http://localhost:8080/api/cakes/$CAKE_ID"
```

An inverted range is rejected:

```bash
curl -s -i "http://localhost:8080/api/cakes?minPrice=50&maxPrice=10"
# HTTP/1.1 400 Bad Request
# {"code":"INVALID_PRICE_RANGE","message":"minPrice ... maxPrice ...","timestamp":"...","path":"/api/cakes"}
```

### 3. Add to the basket

```bash
curl -s -i -X POST "http://localhost:8080/api/baskets/$CUSTOMER/items" \
  -H 'Content-Type: application/json' \
  -d "{\"cakeId\":\"$CAKE_ID\",\"quantity\":2}"
```

`201 Created` for a new basket line, `200 OK` when the cake is already in the basket and the quantity
is incremented.

```json
{
  "customerId": "cust-001",
  "items": [
    {
      "cakeId": "11111111-1111-4111-8111-000000000001",
      "cakeName": "Chocolate Truffle",
      "unitPrice": 23.75,
      "quantity": 2,
      "lineTotal": 47.50
    }
  ],
  "basketTotal": 47.50
}
```

The unit price is captured from the Catalog Service when the item is added, so a later catalog price
change does not move an existing basket. Adding the unavailable seeded cake
(`...00000006`, Lemon Drizzle Cupcake) returns `409 CAKE_UNAVAILABLE` and leaves the basket untouched.

### 4. View and adjust the basket

```bash
curl -s "http://localhost:8080/api/baskets/$CUSTOMER"

# add a second cake
curl -s -X POST "http://localhost:8080/api/baskets/$CUSTOMER/items" \
  -H 'Content-Type: application/json' \
  -d '{"cakeId":"11111111-1111-4111-8111-000000000004","quantity":4}'

# change a quantity
curl -s -X PUT "http://localhost:8080/api/baskets/$CUSTOMER/items/$CAKE_ID" \
  -H 'Content-Type: application/json' \
  -d '{"quantity":1}'

# remove a line
curl -s -X DELETE "http://localhost:8080/api/baskets/$CUSTOMER/items/11111111-1111-4111-8111-000000000004"
```

Each call returns the whole basket with a recalculated `basketTotal`, which always equals the sum of
the line totals.

### 5. Check out

```bash
curl -s -X POST "http://localhost:8080/api/orders" \
  -H 'Content-Type: application/json' \
  -d "{\"customerId\":\"$CUSTOMER\",\"customerEmail\":\"ada@example.com\"}"
```

```json
{
  "orderId": "6f1c2a54-9b3e-4a77-8d21-0c5e7f4b1a90",
  "orderTotal": 23.75,
  "status": "CREATED"
}
```

Checkout carries no item list: the order is built from the stored basket, so a client cannot supply
its own price or quantity. The order is persisted and the basket cleared in one transaction, and the
`order.completed` event is published only after that transaction commits.

```bash
ORDER_ID=6f1c2a54-9b3e-4a77-8d21-0c5e7f4b1a90   # from the response above

curl -s "http://localhost:8080/api/orders/$ORDER_ID"
curl -s "http://localhost:8080/api/baskets/$CUSTOMER"   # now empty, basketTotal 0.00
```

`GET /api/orders/{orderId}` returns `orderId`, `customerId`, `status`, `orderTotal`, `createdAt`, and
the ordered `items`.

### 6. Observe the notification

The Notification Service consumes `order.completed`, composes the confirmation, and records the
attempt.

```bash
curl -s "http://localhost:8080/api/notifications/orders/$ORDER_ID"
```

```json
[
  {
    "id": "b21e5d4a-70c8-4f9b-9a55-3d81c6f2e447",
    "orderId": "6f1c2a54-9b3e-4a77-8d21-0c5e7f4b1a90",
    "channel": "EMAIL",
    "status": "SENT",
    "attemptedAt": "2025-01-14T09:41:22.118Z"
  }
]
```

The delivery channel is a stub that logs the composed confirmation rather than talking to a mail
server, so the body itself is visible in the service log:

```bash
docker compose logs -f notification-service
# or, on Kubernetes
kubectl -n cake-delight logs -f deployment/notification-service
```

Under [path B](#path-b--local-jvm-processes-no-docker) the confirmation is already on screen: the
service runs in the foreground in its own terminal.

A list is returned, not a single record, because failed attempts accumulate for one order. Redelivery
of the same event does not produce a second confirmation: the listener skips an order that already
has a `SENT` record, and on PostgreSQL the partial unique index enforces that at the database level.

Optionally move the order to `CONFIRMED`:

```bash
curl -s -X POST "http://localhost:8080/api/orders/$ORDER_ID/confirmation"
# {"orderId":"6f1c2a54-...","status":"CONFIRMED"}
```

### 7. Rate the cake

```bash
curl -s -X POST "http://localhost:8080/api/cakes/$CAKE_ID/ratings" \
  -H 'Content-Type: application/json' \
  -d "{\"customerId\":\"$CUSTOMER\",\"score\":5}"
```

```json
{
  "id": "9c0a7f61-2b44-4c8e-9f3a-71d0e5b8c012",
  "cakeId": "11111111-1111-4111-8111-000000000001",
  "customerId": "cust-001",
  "score": 5,
  "createdAt": "2025-01-14T09:43:07.554Z"
}
```

```bash
curl -s "http://localhost:8080/api/cakes/$CAKE_ID/ratings"
curl -s "http://localhost:8080/api/cakes/$CAKE_ID/ratings/average"
# {"cakeId":"11111111-...","averageRating":5.0,"ratingCount":1}
```

A score outside 1–5 returns `400 VALIDATION_ERROR` naming the `score` field. A cake with no ratings
returns `200` with a `null` average and `ratingCount` 0.

The gateway route for `/api/cakes/*/ratings/**` is ordered ahead of the broader `/api/cakes/**`
route, which is what sends this request to the Rating Service rather than the Catalog Service.

---

## Postman collection

The same journey as a click-through, for demoing without curl. Two files in `postman/`:

| File | Contents |
|---|---|
| `Cake-Delight.postman_collection.json` | Collection Format v2.1.0, eight folders |
| `Cake-Delight-Local.postman_environment.json` | `baseUrl`, `customerId`, `customerEmail`, `cakeId`, `unavailableCakeId`, `orderId` |

Import both — **Import** in Postman, then select the environment named *Cake Delight - Local* from
the picker at the top right, or the requests will fire at an unresolved `{{baseUrl}}`.

`baseUrl` defaults to `http://localhost:8080`, the gateway, so the collection works against Compose
as-is and against Kubernetes once `kubectl -n cake-delight port-forward svc/api-gateway 8080:8080` is
running. `cakeId` and `unavailableCakeId` default to seeded identifiers from
`V2__seed_cakes.sql`, so nothing needs editing on a freshly started stack.

The folders are numbered in journey order — health, browse, filter, basket, checkout, notification,
ratings, error cases — so running them top to bottom performs the full end-to-end flow. Checkout
captures the generated `orderId` into the environment, which is what lets the order read, the
confirmation, and the notification lookup address the order that was just created; that capture is
the only script in the collection. Every request carries a description explaining what it
demonstrates and the status to expect, so the collection doubles as the demo script.

Two notes for a live demo: `Basket > Add same item again` is the same request as the one before it and
returns `200` rather than `201`, which is the increment-versus-insert behaviour worth pointing at; and
the notification record arrives asynchronously, so on a cold stack the first
`Notification` call can come back as an empty array — re-send it.

---

## Observability

Every service exposes Spring Boot Actuator health with liveness and readiness probe groups, plus
Swagger UI.

| Endpoint | Reports |
|---|---|
| `/actuator/health` | aggregate health |
| `/actuator/health/liveness` | process is alive — used by the Kubernetes liveness probe |
| `/actuator/health/readiness` | dependencies are usable, database included — used by the Kubernetes readiness probe and the Compose health checks |
| `/swagger-ui.html` | interactive API docs |
| `/v3/api-docs` | OpenAPI document |

Under Compose and Kubernetes only the gateway's endpoints are reachable from outside:

```bash
curl -s http://localhost:8080/actuator/health/readiness
curl -s http://localhost:8080/swagger-ui.html
```

The gateway carries no database, so its readiness group is state-only. To open a service's own
Swagger UI, port-forward it:

```bash
# long running, run this yourself
kubectl -n cake-delight port-forward svc/catalog-service 8081:8081
# then http://localhost:8081/swagger-ui.html
```

Every request is logged with a request identifier (`X-Request-Id`, generated when absent), the HTTP
method, the path, the response status, and the elapsed milliseconds. Errors use one shape across all
services:

```json
{ "code": "CAKE_NOT_FOUND", "message": "...", "timestamp": "...", "path": "/api/cakes/..." }
```

---

## Project layout

```
cakeDelight/
├── api-gateway/                # Spring Cloud Gateway, route table only
├── catalog-service/
├── order-service/
├── rating-service/
├── notification-service/
├── web-ui/                     # static browser client served by nginx
├── postman/                    # collection + environment, requests in journey order
├── k8s/
│   ├── README.md               # apply procedure, secrets, scaling, known gaps
│   ├── namespace.yaml
│   ├── postgres/               # one Deployment + Service + PVC per database
│   ├── kafka/                  # single-node KRaft broker
│   ├── web-ui/                 # deployment, service, configmap, hpa
│   └── <component>/            # deployment, service, configmap, secret, hpa
├── docs/
│   ├── OVERVIEW.md             # high-level guide - start here
│   ├── api.md                  # endpoint reference
│   ├── event-contract.md       # order.completed payload contract
│   ├── data-model.md           # four schemas, keys, checks, indexes
│   ├── DEMO.md                 # end-to-end demonstration with captured evidence
│   └── capstone-traceability.md # brief item -> code, with verification status
├── docker-compose.yml
└── README.md
```

Each service directory is an independent Maven project with the same internal shape:

```
<service>/
├── src/main/java/com/cakedelight/<service>/
│   ├── controller/  service/  repository/  domain/  dto/  client/  messaging/  config/
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/           # Flyway, PostgreSQL - source of truth
├── src/test/resources/
│   ├── application-test.yml    # H2 `test` profile
│   └── db/migration-h2/        # hand-maintained H2 mirror
├── Dockerfile
└── pom.xml
```

There is no shared library. Each service keeps its own `ErrorResponse`, `RequestLoggingFilter`, and
its own copy of the event record; the contract between publisher and consumer is
[`docs/event-contract.md`](docs/event-contract.md).

---

## Troubleshooting

**`mvn` is not recognised.** Maven is not on `PATH`. Either add
its `bin` directory to `PATH` and confirm with `mvn -v` that the reported Java version is 21, or
invoke the binary by full path as shown in [path B, step 1](#1-build-all-five).

**`invalid target release: 21` or an unsupported class file version.** `JAVA_HOME` points at an older
JDK. Point it at a JDK 21 installation and reopen the shell.

**`kubectl apply -f k8s/` created only the namespace.** Expected: that command is not recursive. Use
`kubectl apply -R -f k8s/` after applying `k8s/namespace.yaml`.

**`namespaces "cake-delight" not found`.** Apply `k8s/namespace.yaml` before the rest.

**A pod authenticates with the password `REPLACE_ME`.** The committed Secret placeholders were never
replaced. See [Replace the placeholder secrets](#1-replace-the-placeholder-secrets).

**Could not resolve placeholder `SPRING_DATASOURCE_URL`.** A required environment variable is
missing. Nothing is defaulted under the default profile; see the
[configuration table](#configuration). For a local run, add `--spring.profiles.active=local` instead —
the H2 datasource is inlined there.

**The gateway will not start, or every route answers `503 SERVICE_UNAVAILABLE`.** Its four
`*_SERVICE_URL` variables have no defaults, so leaving one unset fails startup on an unresolved
placeholder; setting one to the wrong port, or starting the gateway before the service it points at,
gives the `503` instead. Set all four as in [path B, step 6](#3-start-in-this-order).

**A service stays unready in Compose.** Check its logs for a Flyway failure, then check whether its
database container is healthy: `docker compose ps`.

**Integration tests (`*IT`) fail to start containers.** Testcontainers needs a running Docker daemon.
`mvn test` alone runs the unit and web-slice tests, which do not.

**`The input line is too long` when starting Kafka.** `kafka-storage.bat` and `kafka-server-start.bat`
assemble a literal classpath from all 119 jars in `libs\`, which overruns the Windows command-line
limit. Do not use the scripts: invoke `java -cp "<kafka>\libs\*" ...` and let the JVM expand the
wildcard. See [path B, Kafka setup](#2-one-time-kafka-setup).

**`Could not find or load main class .configuration=...`.** PowerShell mangled the `-D` system-property
arguments. Run the broker through `cmd` instead: `cmd /c kafka-run.cmd`.

**`403` with an empty body on every POST, PUT, and DELETE from the browser, while GETs work.** The
gateway is rejecting the UI's `Origin`. Browsers send `Origin` on writes but not on same-origin GETs,
which is exactly why reads look fine. Allow the UI origin under `spring.cloud.gateway.globalcors`, or
set `CORS_ALLOWED_ORIGIN_PATTERNS` to override the default `http://localhost:*`. See
[Web UI](#web-ui).

**UI changes are not appearing.** The tab is running the JavaScript it parsed on first load. Hard-refresh
with Ctrl+F5; the dev server already sends `Cache-Control: no-store`, so nothing else is caching it.

**`400 BASKET_EMPTY` on checkout, right after a checkout succeeded.** Correct behaviour: checkout clears
the basket in the same transaction that creates the order. Add an item again before checking out again.

**A basket, order, rating, or notification vanished.** A service was restarted. Under the `local`
profile the database lives in the JVM, so a restart wipes the tables and replays the migrations — the
seeded catalogue returns, everything else does not. Only the Kafka log in `%USERPROFILE%\kdata` survives.
