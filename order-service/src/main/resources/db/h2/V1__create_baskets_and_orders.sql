-- Cake Delight - Order Service schema (H2 translation - `local` profile only)
--
-- H2-compatible translation of db/migration/V1__create_baskets_and_orders.sql. PostgreSQL 16
-- remains the canonical production target and db/migration stays authoritative; this copy exists
-- only so the service can run without a PostgreSQL instance. Keep the two in sync by hand: the
-- entities run with `ddl-auto: validate`, so any column drift fails startup, which is the point.
--
-- Owns baskets and orders only. cake_id is a plain UUID column in every table here: cake data
-- lives in catalog_db and is owned by the Catalog Service, so no foreign key is possible
-- (Requirement 10.1). cake_name and unit_price are snapshots captured from the Catalog Service
-- at the time the item was added, so an order reads back unchanged later (Requirements 5.1, 5.6).
-- The only foreign key is order_items -> orders, which lives inside this database.
--
-- DIVERGENCE FROM POSTGRESQL: `orders.created_at` is declared `TIMESTAMP WITH TIME ZONE` because
-- H2 does not accept the PostgreSQL `TIMESTAMPTZ` alias. Same semantics and same JDBC type; the
-- PostgreSQL migration keeps `TIMESTAMPTZ`. Nothing else diverges: table names, column names,
-- column order, types, nullability, CHECK constraints, the composite UNIQUE constraint and the
-- ON DELETE CASCADE foreign key are all supported by H2 and reproduced verbatim.
CREATE TABLE basket_items (
    id          UUID PRIMARY KEY,
    customer_id VARCHAR(100)  NOT NULL,
    cake_id     UUID          NOT NULL,
    cake_name   VARCHAR(150)  NOT NULL,
    unit_price  NUMERIC(12,2) NOT NULL CHECK (unit_price >= 0),
    quantity    INTEGER       NOT NULL CHECK (quantity > 0),
    CONSTRAINT uq_basket_customer_cake UNIQUE (customer_id, cake_id)
);

-- Backs the basket read by customer (Requirement 4.1) and the checkout clear (Requirement 5.3).
CREATE INDEX idx_basket_items_customer ON basket_items (customer_id);

CREATE TABLE orders (
    id             UUID PRIMARY KEY,
    customer_id    VARCHAR(100)             NOT NULL,
    customer_email VARCHAR(255)             NOT NULL,
    total          NUMERIC(12,2)            NOT NULL CHECK (total >= 0),
    status         VARCHAR(20)              NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Backs order lookups by customer (Requirement 5.1).
CREATE INDEX idx_orders_customer ON orders (customer_id);

CREATE TABLE order_items (
    id         UUID PRIMARY KEY,
    order_id   UUID          NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    cake_id    UUID          NOT NULL,
    cake_name  VARCHAR(150)  NOT NULL,
    unit_price NUMERIC(12,2) NOT NULL CHECK (unit_price >= 0),
    quantity   INTEGER       NOT NULL CHECK (quantity > 0)
);

-- Backs loading an order's line items (Requirements 5.1, 5.6).
CREATE INDEX idx_order_items_order ON order_items (order_id);
