# Product: Cake Delight

Cake Delight is a cloud-native microservices application built as a capstone project. It delivers one
end-to-end customer journey: browse cakes, filter them, manage a basket, check out, rate cakes, and
receive an order confirmation.

## Services and ownership

| Service | Owns | Responsibility |
|---|---|---|
| Catalog Service | Cake products | List, filter, and retrieve cakes |
| Order Service | Baskets, Orders | Basket CRUD, totals, checkout, publishes order completion event |
| Rating Service | Ratings | Submit ratings, expose average rating per cake |
| Notification Service | Notification records | Consumes order completion event, sends confirmation |
| API Gateway | Routing config | Single client entry point |
| Web UI | Nothing | Static browser client for the customer journey; calls the gateway only |

The Web UI is the "client application or user interface" component of the architecture. It holds no
data and no business rules: every read and write goes through the gateway on port 8080. It ships as a
container in `docker-compose.yml` and as a Deployment plus Service in `k8s/`.

## Scope discipline

This project is delivered **incrementally**. The current increment is the core happy path plus basic
validation, error handling, health checks, and logging, and it now also includes:

- **Retry with bounded exponential backoff** on the cross-service catalog read, retrying only
  transient failures (connect failure, read timeout, 5xx). Configured under
  `catalog.service.retry.*` in `order-service`.
- **HorizontalPodAutoscaler** per component, plus the CPU requests the HPA needs to compute
  utilization, so the deployment has a scaling story.
- **A browser client** exercising the whole journey through the gateway.

Do not add the following unless the user explicitly asks:

- Authentication, authorization, or JWT handling
- Payment processing
- Inventory or stock decrement
- Transactional outbox, exactly-once delivery, dead-letter queues, event schema versioning
- Circuit breakers and bulkheads — retry is in scope, failure isolation is not
- Service mesh, sidecars, or resource tuning beyond the requests the HPA needs
- Distributed tracing, metrics dashboards, Prometheus/Grafana
- Multi-channel notification fan-out

When a requirement is ambiguous, pick the simplest implementation that satisfies the acceptance
criteria and demonstrates the flow. Prefer working software over defensive completeness.

## Definition of done for the capstone

- All four services plus gateway and the web UI run together via Docker Compose
- All four services plus gateway and the web UI deploy to Kubernetes with Deployment and Service
  manifests, plus a HorizontalPodAutoscaler per component
- The end-to-end flow is demonstrable: browse -> filter -> basket -> checkout -> event -> notification -> rating
- The flow is drivable from the web UI, not curl alone
- The scaling story is stated: what each component scales on, and where scaling stops helping
  (notification-service consumption is capped by the single topic partition)
- Transient cross-service failures are retried rather than surfaced on the first attempt
- API documentation exists for every exposed endpoint
- Setup and run instructions exist in the README
- Requirements from the capstone brief are traceable to code in `docs/capstone-traceability.md`
