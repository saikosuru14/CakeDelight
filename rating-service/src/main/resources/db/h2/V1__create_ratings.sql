-- Cake Delight - Rating Service schema (H2 translation - `local` profile only)
--
-- H2-compatible translation of db/migration/V1__create_ratings.sql. PostgreSQL 16 remains the
-- canonical production target and db/migration stays authoritative; this copy exists only so the
-- service can run without a PostgreSQL instance. Keep the two in sync by hand: the entities run
-- with `ddl-auto: validate`, so any column drift fails startup, which is the point.
--
-- Owns ratings only. cake_id is a plain UUID column: cake data lives in catalog_db and is owned by
-- the Catalog Service, so no foreign key is possible here (Requirement 10.1). The CHECK below is
-- the database level guarantee behind the average-range invariant (Requirement 7.7). Multiple
-- ratings per customer per cake are allowed by design.
--
-- DIVERGENCE FROM POSTGRESQL: `created_at` is declared `TIMESTAMP WITH TIME ZONE` because H2 does
-- not accept the PostgreSQL `TIMESTAMPTZ` alias. Same semantics and same JDBC type; the PostgreSQL
-- migration keeps `TIMESTAMPTZ`. Table name, column names, column order, nullability and the CHECK
-- constraint are otherwise identical.
CREATE TABLE ratings (
    id          UUID                     PRIMARY KEY,
    cake_id     UUID                     NOT NULL,
    customer_id VARCHAR(100)             NOT NULL,
    score       INTEGER                  NOT NULL CHECK (score BETWEEN 1 AND 5),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Backs both the rating list (Requirement 7.4) and the average aggregate (Requirement 7.5).
CREATE INDEX idx_ratings_cake ON ratings (cake_id);
