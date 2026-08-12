# Requirements Document

## Introduction

Cake Delight is a cloud-native application built as four independently deployable Spring Boot microservices behind an API Gateway, with one database per owning service and a message broker for asynchronous order events. This document covers the core end-to-end flow: browsing and filtering the cake catalog, managing a basket, checking out, rating cakes, and receiving an order confirmation. The project is delivered incrementally, so this document intentionally scopes only the minimum needed to build, run, and demonstrate that flow. Anything beyond it is listed under Out of Scope and revisited in later increments.

## Glossary

- **API_Gateway**: The single externally reachable entry point that routes client requests to the correct microservice.
- **Catalog_Service**: The microservice that owns Cake data and exposes APIs to list, filter, and retrieve Cakes.
- **Order_Service**: The microservice that owns Basket and Order data, performs checkout, and publishes the Order_Completed_Event.
- **Rating_Service**: The microservice that owns Rating data and exposes APIs to submit Ratings and retrieve the Average_Rating.
- **Notification_Service**: The microservice that consumes the Order_Completed_Event and sends order confirmation notifications.
- **Message_Broker**: The messaging component that transports the Order_Completed_Event from the Order_Service to the Notification_Service.
- **Cake**: A product record containing a cake identifier, name, description, Cake_Category, price, Availability_Flag, and image reference.
- **Cake_Category**: A classification label assigned to a Cake, such as "birthday", "cupcake", or "pastry".
- **Availability_Flag**: A boolean attribute on a Cake indicating whether the Cake can currently be purchased.
- **Basket**: A collection of Basket_Items owned by a single Customer_Id and managed by the Order_Service.
- **Basket_Item**: An entry in a Basket containing a cake identifier, a unit price captured when the item was added, and a quantity.
- **Line_Total**: The unit price of a Basket_Item multiplied by the quantity of that Basket_Item, rounded to two decimal places.
- **Basket_Total**: The sum of the Line_Totals of all Basket_Items in a Basket.
- **Order**: A record created at checkout containing an order identifier, a Customer_Id, the ordered items, the order total, an Order_Status, and a creation timestamp.
- **Order_Status**: The state of an Order, one of CREATED or CONFIRMED.
- **Order_Completed_Event**: The event published by the Order_Service after a successful checkout, carrying the order identifier, Customer_Id, ordered items, order total, and customer contact details.
- **Rating**: A record containing a cake identifier, a Customer_Id, a Rating_Score, and a submission timestamp.
- **Rating_Score**: An integer value between 1 and 5 inclusive submitted as part of a Rating.
- **Average_Rating**: The arithmetic mean of all Rating_Score values stored for one cake identifier, rounded to one decimal place.
- **Customer_Id**: The identifier supplied by the client that associates a Basket, an Order, or a Rating with a single customer.
- **Notification_Status**: The delivery state of an order confirmation notification, one of SENT or FAILED.
- **Health_Endpoint**: An HTTP endpoint exposed by each microservice that reports service liveness and readiness.

## Requirements

### Requirement 1: Browse and View Cakes

**User Story:** As a customer, I want to browse the cake catalog and open a cake, so that I can see what Cake Delight offers and decide what to buy.

#### Acceptance Criteria

1. WHEN the Catalog_Service receives a request to list Cakes, THE Catalog_Service SHALL return HTTP status 200 with a paginated collection of Cakes, each carrying the cake identifier, name, description, Cake_Category, price, Availability_Flag, and image reference.
2. WHEN a list request omits pagination parameters, THE Catalog_Service SHALL return the first page with a page size of 20.
3. WHEN the Catalog_Service returns a page of Cakes, THE Catalog_Service SHALL include the applied page number, the applied page size, and the total record count in the response.
4. WHEN no stored Cake matches a list request, THE Catalog_Service SHALL return HTTP status 200 with an empty collection and a total record count of 0.
5. WHEN the Catalog_Service receives a request for a cake identifier that exists, THE Catalog_Service SHALL return HTTP status 200 with the full field set of that Cake.
6. IF the Catalog_Service receives a request for a cake identifier that is not stored, THEN THE Catalog_Service SHALL return HTTP status 404 with an error message containing the requested cake identifier.

### Requirement 2: Filter Cakes by Name, Category, and Price Range

**User Story:** As a customer, I want to filter cakes by name, category, and price range, so that I can find the cake I want without scanning the whole catalog.

#### Acceptance Criteria

1. WHEN a list request includes a name filter, THE Catalog_Service SHALL return only the Cakes whose name contains the filter value as a case-insensitive substring.
2. WHEN a list request includes a Cake_Category filter, THE Catalog_Service SHALL return only the Cakes whose Cake_Category equals the filter value using case-insensitive comparison.
3. WHEN a list request includes a minimum price, a maximum price, or both, THE Catalog_Service SHALL return only the Cakes whose price falls within the supplied bounds inclusive.
4. WHEN a list request includes more than one filter, THE Catalog_Service SHALL return only the Cakes that satisfy every supplied filter.
5. IF a list request supplies a minimum price greater than the supplied maximum price, THEN THE Catalog_Service SHALL return HTTP status 400 with a validation error message naming both price parameters.
6. IF a list request supplies a non-numeric or negative price parameter, THEN THE Catalog_Service SHALL return HTTP status 400 with a validation error message naming the offending parameter.

### Requirement 3: Add Cakes to the Basket

**User Story:** As a customer, I want to add a selected cake to my basket, so that I can buy the cake at checkout.

#### Acceptance Criteria

1. WHEN the Order_Service receives a request to add a cake identifier with a positive integer quantity for a Customer_Id that has no Basket, THE Order_Service SHALL create a Basket for that Customer_Id containing that Basket_Item and SHALL return HTTP status 201 with the Basket contents and the Basket_Total.
2. WHEN the Order_Service adds a new Basket_Item, THE Order_Service SHALL retrieve the Cake from the Catalog_Service and SHALL store the retrieved price as the unit price of that Basket_Item.
3. WHEN the Order_Service receives a request to add a cake identifier that is already present in the Basket, THE Order_Service SHALL increase the quantity of the existing Basket_Item by the requested quantity and SHALL return HTTP status 200 with the Basket contents and the Basket_Total.
4. IF a request to add a Basket_Item omits the quantity or supplies a quantity that is not a positive integer, THEN THE Order_Service SHALL return HTTP status 400 with a validation error message naming the quantity parameter and SHALL leave the Basket unchanged.
5. IF a request to add a Basket_Item specifies a cake identifier that the Catalog_Service reports as not stored, THEN THE Order_Service SHALL return HTTP status 404 with an error message containing the cake identifier and SHALL leave the Basket unchanged.
6. IF a request to add a Basket_Item specifies a Cake whose Availability_Flag is false, THEN THE Order_Service SHALL return HTTP status 409 with an error message stating that the Cake is unavailable and SHALL leave the Basket unchanged.

### Requirement 4: View, Update, and Remove Basket Items

**User Story:** As a customer, I want to view my basket and change its contents, so that I can control what I buy before checkout.

#### Acceptance Criteria

1. WHEN the Order_Service receives a request to view the Basket for a Customer_Id, THE Order_Service SHALL return HTTP status 200 with each Basket_Item's cake identifier, cake name, unit price, quantity, and Line_Total, together with the Basket_Total.
2. WHEN the Order_Service receives a request to view the Basket for a Customer_Id that has no Basket, THE Order_Service SHALL return HTTP status 200 with an empty Basket_Item collection and a Basket_Total of 0.00.
3. WHEN the Order_Service receives a request to update an existing Basket_Item to a positive integer quantity, THE Order_Service SHALL replace the stored quantity with the requested quantity and SHALL return HTTP status 200 with the recalculated Basket_Total.
4. WHEN the Order_Service receives a request to remove an existing Basket_Item, THE Order_Service SHALL delete that Basket_Item and SHALL return HTTP status 200 with the recalculated Basket_Total.
5. IF a request to update or remove a Basket_Item targets a cake identifier that is absent from the Basket, THEN THE Order_Service SHALL return HTTP status 404 with an error message containing the cake identifier and SHALL leave the Basket unchanged.
6. FOR ALL Baskets, THE Order_Service SHALL report a Basket_Total that equals the sum of the Line_Totals of the Basket_Items in that Basket (invariant property).

### Requirement 5: Complete Checkout and Create an Order

**User Story:** As a customer, I want to check out my basket, so that my order is placed and recorded.

#### Acceptance Criteria

1. WHEN the Order_Service receives a checkout request for a Customer_Id whose Basket contains at least one Basket_Item, THE Order_Service SHALL create one Order containing the cake identifier, unit price, and quantity of every Basket_Item, with an order total equal to the Basket_Total and an Order_Status of CREATED.
2. WHEN the Order_Service creates an Order at checkout, THE Order_Service SHALL return HTTP status 201 with the order identifier, the order total, and the Order_Status.
3. WHEN the Order_Service commits an Order at checkout, THE Order_Service SHALL clear the Basket of that Customer_Id in the same transaction that persists the Order.
4. IF the Order_Service receives a checkout request for a Customer_Id whose Basket contains no Basket_Item, THEN THE Order_Service SHALL return HTTP status 400 with an error message stating that the Basket is empty and SHALL create no Order.
5. IF a checkout request omits the customer email address or supplies an email address that does not match the accepted email format, THEN THE Order_Service SHALL return HTTP status 400 with a validation error message naming the email field and SHALL create no Order.
6. WHEN the Order_Service receives a request for an order identifier that exists, THE Order_Service SHALL return HTTP status 200 with the order identifier, Customer_Id, Order_Status, order total, creation timestamp, and ordered items.
7. IF the Order_Service receives a request for an order identifier that is not stored, THEN THE Order_Service SHALL return HTTP status 404 with an error message containing the requested order identifier.

### Requirement 6: Publish the Order Completion Event

**User Story:** As an operator, I want an event published when an order completes, so that downstream services can react without the Order Service calling them directly.

#### Acceptance Criteria

1. WHEN the Order_Service has successfully committed an Order at checkout, THE Order_Service SHALL publish one Order_Completed_Event to the Message_Broker.
2. WHEN the Order_Service publishes an Order_Completed_Event, THE Order_Service SHALL include the order identifier, the Customer_Id, the ordered items, the order total, and the customer contact details in the event payload.
3. IF a checkout request fails validation or the Order is not committed, THEN THE Order_Service SHALL publish no Order_Completed_Event.
4. IF the Message_Broker is unavailable when the Order_Service attempts to publish an Order_Completed_Event, THEN THE Order_Service SHALL log the failure with the order identifier at ERROR level and SHALL still return HTTP status 201 for the committed checkout.
5. WHEN the Order_Status of an Order changes to CONFIRMED, THE Order_Service SHALL return HTTP status 200 with the order identifier and the updated Order_Status.

### Requirement 7: Submit and Retrieve Cake Ratings

**User Story:** As a customer, I want to rate a cake and see its average rating, so that I can share and read feedback about products.

#### Acceptance Criteria

1. WHEN the Rating_Service receives a Rating carrying a cake identifier, a Customer_Id, and a Rating_Score between 1 and 5 inclusive, THE Rating_Service SHALL store the Rating and SHALL return HTTP status 201 with the stored Rating.
2. IF a submitted Rating carries a Rating_Score that is not an integer between 1 and 5 inclusive, THEN THE Rating_Service SHALL return HTTP status 400 with a validation error message naming the Rating_Score field and SHALL store no Rating.
3. IF a submitted Rating omits the cake identifier or the Customer_Id, THEN THE Rating_Service SHALL return HTTP status 400 with a validation error message naming the missing field and SHALL store no Rating.
4. WHEN the Rating_Service receives a request for the Ratings of a cake identifier, THE Rating_Service SHALL return HTTP status 200 with the stored Ratings for that cake identifier.
5. WHEN the Rating_Service receives a request for the Average_Rating of a cake identifier that has at least one stored Rating, THE Rating_Service SHALL return HTTP status 200 with the Average_Rating and the total Rating count for that cake identifier.
6. WHEN the Rating_Service receives a request for the Average_Rating of a cake identifier that has no stored Rating, THE Rating_Service SHALL return HTTP status 200 with a null Average_Rating and a Rating count of 0.
7. FOR ALL cake identifiers with at least one stored Rating, THE Rating_Service SHALL report an Average_Rating between 1.0 and 5.0 inclusive (invariant property).

### Requirement 8: Send Order Confirmation Notifications

**User Story:** As a customer, I want to receive an order confirmation after checkout, so that I know my order was placed.

#### Acceptance Criteria

1. WHEN the Notification_Service receives an Order_Completed_Event from the Message_Broker, THE Notification_Service SHALL send an order confirmation containing the order identifier, the ordered items, and the order total to the customer contact details carried in the event.
2. WHEN the Notification_Service sends an order confirmation, THE Notification_Service SHALL store a notification record with the order identifier, the delivery channel, the Notification_Status, and the attempt timestamp.
3. IF the configured delivery channel rejects an order confirmation, THEN THE Notification_Service SHALL set the Notification_Status to FAILED and SHALL log the order identifier and the failure reason at ERROR level.
4. IF the Notification_Service receives an Order_Completed_Event for an order identifier that already has a notification record with a Notification_Status of SENT, THEN THE Notification_Service SHALL send no further order confirmation for that order identifier.
5. WHEN the Notification_Service receives a request for the notification records of an order identifier, THE Notification_Service SHALL return HTTP status 200 with the stored notification records for that order identifier.
6. FOR ALL order identifiers, THE Notification_Service SHALL send at most one successful order confirmation (invariant property).

### Requirement 9: Route Client Requests Through the API Gateway

**User Story:** As a client developer, I want one entry point for the whole system, so that I do not have to know the address of each microservice.

#### Acceptance Criteria

1. THE API_Gateway SHALL be the only component of Cake Delight exposed to clients.
2. WHEN the API_Gateway receives a request whose path matches a configured route, THE API_Gateway SHALL forward the request to the microservice that owns that route and SHALL return that microservice's response to the client.
3. IF the API_Gateway receives a request whose path matches no configured route, THEN THE API_Gateway SHALL return HTTP status 404 with an error message stating that the route is unknown.
4. IF the microservice targeted by a routed request is unreachable, THEN THE API_Gateway SHALL return HTTP status 503 with an error message identifying the unavailable service.

### Requirement 10: Own Data per Service

**User Story:** As an architect, I want each service to own its own data, so that services stay independently deployable.

#### Acceptance Criteria

1. THE Catalog_Service, THE Order_Service, THE Rating_Service, and THE Notification_Service SHALL each read and write exactly one dedicated database.
2. WHEN a microservice needs data owned by another microservice, THE requesting microservice SHALL obtain that data through the owning microservice's REST API or through the Message_Broker.
3. IF a microservice is configured with connection details for a database owned by another microservice, THEN THE configuration SHALL be treated as invalid and THE microservice SHALL fail to start with a logged configuration error.

### Requirement 11: Containerize and Deploy on Kubernetes

**User Story:** As a developer, I want each service containerized and deployable to Kubernetes, so that I can run the system locally and in a cluster.

#### Acceptance Criteria

1. THE repository SHALL provide one Dockerfile per microservice and one for the API_Gateway that each produce a runnable container image.
2. THE repository SHALL provide a Docker Compose file that starts the API_Gateway, all four microservices, their databases, and the Message_Broker for local execution.
3. THE repository SHALL provide a Kubernetes Deployment manifest and a Kubernetes Service manifest for the API_Gateway and for each microservice.
4. WHEN the replica count of a microservice Deployment is increased, THE microservice SHALL serve requests from every running replica without requiring a change to its container image.
5. THE Kubernetes Deployment manifests SHALL read service configuration and credentials from ConfigMaps and Secrets rather than from values hard-coded in the container image.

### Requirement 12: Provide Basic Reliability and Observability

**User Story:** As an operator, I want validated inputs, consistent errors, health checks, and useful logs, so that I can run and troubleshoot the system.

#### Acceptance Criteria

1. WHEN a microservice receives a request whose body or parameters violate a stated validation rule, THE microservice SHALL return HTTP status 400 with an error response naming the offending field.
2. WHEN a microservice returns an error response, THE microservice SHALL use a single error response format containing an error code, a human-readable message, and a timestamp.
3. IF a microservice encounters an unhandled exception while processing a request, THEN THE microservice SHALL return HTTP status 500 with the standard error response format and SHALL log the exception at ERROR level.
4. THE Catalog_Service, THE Order_Service, THE Rating_Service, THE Notification_Service, and THE API_Gateway SHALL each expose a Health_Endpoint reporting liveness and readiness state.
5. WHEN a microservice handles a request, THE microservice SHALL write a structured log entry containing the request identifier, the HTTP method, the path, the response status, and the elapsed time.

## Out of Scope (Future Increments)

The following are deliberately deferred and are not part of this increment:

- Authentication and authorization of clients, customers, and service-to-service calls
- Payment processing and payment provider integration
- Inventory tracking and stock decrement at checkout
- Transactional outbox and exactly-once event delivery guarantees
- Circuit breakers and advanced resilience policies for inter-service calls
- Horizontal pod autoscaling policies and resource tuning
- Distributed tracing and metrics dashboards
- Multi-channel notification fan-out and notification retry scheduling
