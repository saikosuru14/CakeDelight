# `order.completed` Event Contract

The Order Service publishes one `order.completed` event per committed checkout; the Notification
Service consumes it and sends the order confirmation. This document is the contract between them.

It has to be, because **there is no shared library**. Each side keeps its own copy of the payload
record:

| Side | Records |
|---|---|
| Publisher | `order-service/.../messaging/OrderCompletedEvent.java`, `OrderCompletedItem.java` |
| Consumer | `notification-service/.../messaging/OrderCompletedEvent.java`, `OrderCompletedItem.java` |

Nothing at compile time links the two. The JSON field names below are the only thing keeping them
compatible; renaming a component on one side breaks deserialization on the other silently.

## Topic

Declared in `order-service/.../config/KafkaTopicConfig.java` via `TopicBuilder`. Spring Boot's
auto-configured `KafkaAdmin` creates it at startup when the broker allows it; an unreachable broker is
logged and does not stop the service.

| Setting | Value | Why |
|---|---|---|
| Topic | `order.completed` | Constant `KafkaTopicConfig.ORDER_COMPLETED_TOPIC`, mirrored literally in the consumer's `@KafkaListener(topics = "order.completed")` |
| Partitions | 1 | One broker locally and in the single-node cluster used for the capstone |
| Replication factor | 1 | Same reason |
| Message key | The order identifier as a string (`event.orderId().toString()`) | All events for one order land on the same partition, so per-order ordering holds |
| Key serializer / deserializer | `StringSerializer` / `StringDeserializer` | |
| Value serializer / deserializer | `JsonSerializer` / `JsonDeserializer` | |
| Consumer group | `notification-service` | Set in `application.yml` as `spring.kafka.consumer.group-id` and restated on `@KafkaListener(groupId = ...)`; both must stay the same |
| Producer `acks` | `all` | |
| Consumer `auto-offset-reset` | `earliest` | A consumer joining late still sees events already on the topic |

### Type resolution

The publisher sends **no type headers** (`spring.json.add.type.headers: false`), so the consumer
cannot infer the target class from the record. It declares the type explicitly instead:

```yaml
spring.json.use.type.headers: false
spring.json.value.default.type: com.cakedelight.notification.messaging.OrderCompletedEvent
spring.json.trusted.packages: com.cakedelight.notification.messaging
```

Consequences worth knowing before refactoring:

- Moving or renaming the consumer-side `OrderCompletedEvent` package stops the consumer
  deserializing anything, even though the JSON is unchanged.
- Only `com.cakedelight.notification.messaging` is trusted, so no other type can be coerced out of a
  record on this topic.
- `JsonSerializer`/`JsonDeserializer` are instantiated by the Kafka client from the class names above,
  so they use Spring Kafka's `JacksonUtils.enhancedObjectMapper()`: `Instant` serializes as an
  ISO-8601 string rather than a numeric timestamp, `UUID` as a string, and unknown properties are
  ignored on read. Additive fields are therefore tolerated by an older consumer; renamed or removed
  fields are not.

## Payload

Top-level `OrderCompletedEvent`:

| Field | JSON type | Java type | Notes |
|---|---|---|---|
| `orderId` | string | `UUID` | Also the message key |
| `customerId` | string | `String` | Client-supplied customer identifier |
| `customerEmail` | string | `String` | Confirmation recipient; the consumer's `EmailChannel` rejects a null or blank value and the attempt is recorded `FAILED` |
| `orderTotal` | number | `BigDecimal` | Committed order total, scale 2 HALF_UP |
| `createdAt` | string | `Instant` | ISO-8601, order creation timestamp |
| `items` | array | `List<OrderCompletedItem>` | Normalized to an empty list when absent or null, on both sides |

Each `items[]` element, `OrderCompletedItem`:

| Field | JSON type | Java type | Notes |
|---|---|---|---|
| `cakeId` | string | `UUID` | |
| `cakeName` | string | `String` | Name as captured at checkout |
| `unitPrice` | number | `BigDecimal` | Scale 2 HALF_UP |
| `quantity` | number | `int` | Always positive |

Not in the payload: order status (always `CREATED` at publish time), line totals (the consumer derives
them from `unitPrice * quantity` for the confirmation body), and anything about the basket.

### Monetary scale is part of the contract

`orderTotal` and every `items[].unitPrice` are normalized to scale 2 `HALF_UP` in the compact
constructor of **both** copies of the records. This is not cosmetic: records derive `equals` from
their components and `BigDecimal.equals` is scale-sensitive, so `24.5` and `24.50` are unequal
`BigDecimal`s. Normalizing on construction is what makes a serialize/deserialize round trip compare
equal, and it is what keeps the total rendered in the confirmation identical to the total that was
published. Dropping the normalization from either side breaks equality without breaking parsing.

### Sample

```json
{
  "orderId": "3c2b1a09-8d7e-4f6a-b5c4-d3e2f1a09b8c",
  "customerId": "cust-1042",
  "customerEmail": "ada@example.com",
  "orderTotal": 61.50,
  "createdAt": "2025-05-04T10:15:30.981Z",
  "items": [
    {
      "cakeId": "7f1c8a52-0f6b-4a3f-9c4e-6f9e2b1d0a11",
      "cakeName": "Classic Chocolate Fudge",
      "unitPrice": 24.50,
      "quantity": 2
    },
    {
      "cakeId": "9a0e77b4-31cd-4b62-8a5f-2e4c9d8b1f30",
      "cakeName": "Lemon Drizzle Cupcake",
      "unitPrice": 4.25,
      "quantity": 3
    }
  ]
}
```

Message key for this record: `3c2b1a09-8d7e-4f6a-b5c4-d3e2f1a09b8c`.

## Publish semantics

`CheckoutService` does not send to Kafka. It raises the payload as a Spring application event inside
its transaction; `OrderCompletedPublisher` receives it with
`@TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)` and sends from there.

| Situation | Behaviour |
|---|---|
| Checkout commits | Exactly one record on `order.completed`, keyed by the order identifier |
| Checkout rejected or rolled back | Nothing published |
| Broker unreachable, send throws | Logged at ERROR as `Failed to publish order.completed for orderId={}`, checkout still answers `201` |
| Broker rejects asynchronously | Same ERROR message from the `whenComplete` callback, nothing propagated |

No retry, no outbox, no replay store. A failed publish means that order gets no confirmation.

## Consume semantics

`OrderCompletedListener` guards idempotency in two layers, and both are needed for "at most one
successful confirmation per order":

1. **Pre-send check.** If a `SENT` notification record already exists for `orderId`, the event is
   skipped and logged at INFO before the channel is touched. This handles ordinary redelivery. It has
   to live in the listener rather than in the service, because skipping means "do not deliver", not
   merely "do not insert".
2. **Partial unique index** `uq_notifications_order_sent` on `(order_id) WHERE status = 'SENT'`. Two
   deliveries processed concurrently can both pass the check; the database rejects the losing insert
   and the resulting `DataIntegrityViolationException` is caught and logged at WARN.

A channel rejection produces a `FAILED` record plus an ERROR log, and `FAILED` records accumulate for
one order — only `SENT` is constrained to one. No dead-letter topic, no retry backoff, no
exactly-once consumption; an exception is never propagated out of the listener for a case these two
layers already cover.

## Changing this contract

There is no schema registry and no version field. In practice:

| Change | Safe? |
|---|---|
| Add a field | Yes for a new publisher against an old consumer — unknown properties are ignored |
| Rename or remove a field | No — the consumer silently reads `null` |
| Change monetary scale on one side only | No — parses fine, breaks record equality and the rendered total |
| Move the consumer-side record's package | No — `spring.json.value.default.type` and the trusted-packages list both point at it |

Update both copies of the records and this document in the same change.
