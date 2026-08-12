# Repository Structure

```
cakeDelight/
├── api-gateway/                # Spring Cloud Gateway
├── catalog-service/
├── order-service/
├── rating-service/
├── notification-service/
├── k8s/                        # Kubernetes manifests, grouped by component
│   ├── namespace.yaml
│   ├── postgres/
│   ├── kafka/
│   ├── api-gateway/
│   ├── catalog-service/
│   ├── order-service/
│   ├── rating-service/
│   └── notification-service/
├── docs/
│   ├── api.md                  # endpoint reference
│   └── event-contract.md       # OrderCompletedEvent payload contract
├── docker-compose.yml
└── README.md                   # setup and execution instructions
```

Each service directory is a self-contained Maven project:

```
<service>/
├── src/main/java/com/cakedelight/<service>/
│   ├── <Service>Application.java
│   ├── controller/             # REST endpoints, request/response DTOs only
│   ├── service/                # business logic
│   ├── repository/             # Spring Data interfaces
│   ├── domain/                 # JPA entities
│   ├── dto/                    # request and response records
│   ├── client/                 # outbound calls to other services
│   ├── messaging/              # publishers and listeners
│   └── config/                 # beans, Kafka topic config, exception handler
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/           # Flyway V1__*.sql
├── src/test/java/...
├── Dockerfile
└── pom.xml
```

## Layering rules

- Controllers accept and return DTOs. Never expose JPA entities across the HTTP boundary.
- Controllers hold no business logic; they delegate to a service class.
- Only repositories touch the database. Only the owning service's repositories touch its schema.
- Cross-service reads go through `client/`. Cross-service writes never happen.
- Domain entities carry no annotations from web or messaging layers.

## Messaging

- Kafka topic: `order.completed`, 1 partition and replication factor 1 for local and single-node
  cluster runs.
- Message key: the order identifier, so all events for one order land on the same partition.
- Consumer group: `notification-service`.
- Payload is JSON, serialized with `JsonSerializer` / `JsonDeserializer`.
- The event payload is defined in `docs/event-contract.md`. Both the publisher and the consumer
  keep their own copy of the payload record; there is no shared library between services.

## Naming

- Java packages: `com.cakedelight.<service>` (lowercase, no hyphens).
- Maven `artifactId` and directory name: kebab-case, e.g. `catalog-service`.
- Database per service: `catalog_db`, `order_db`, `rating_db`, `notification_db`.
- Kubernetes resource names match the service directory name.
