-- V1__create_ratings.sql  (H2 mirror - TEST ONLY)
--
-- Test-only mirror of src/main/resources/db/migration/V1__create_ratings.sql, used by the `test`
-- Spring profile (src/test/resources/application-test.yml). db/migration/ remains the source of
-- truth for PostgreSQL 16; nothing here ships in the application jar.
--
-- CHANGED FOR H2:
--   * `created_at` is spelled `TIMESTAMP WITH TIME ZONE` instead of the PostgreSQL-only alias
--     `TIMESTAMPTZ`. H2 does not recognise `TIMESTAMPTZ` as a type name, even in MODE=PostgreSQL.
--     It is the same SQL type with the same semantics and the same JDBC mapping
--     (java.time.OffsetDateTime), so Hibernate validation and time-zone handling are unchanged.
--
-- Everything else matches the PostgreSQL migration exactly: table name, column names, column
-- order, types, nullability, the CHECK constraint and the index, so `ddl-auto: validate` still
-- catches entity drift.
--
-- Owns ratings only. cake_id is a plain UUID column: cake data lives in catalog_db and is owned
-- by the Catalog Service, so no foreign key is possible here (Requirement 10.1). The CHECK below
-- is the database level guarantee behind the average-range invariant (Requirement 7.7). Multiple
-- ratings per customer per cake are allowed by design.
CREATE TABLE ratings (
    id          UUID                     PRIMARY KEY,
    cake_id     UUID                     NOT NULL,
    customer_id VARCHAR(100)             NOT NULL,
    score       INTEGER                  NOT NULL CHECK (score BETWEEN 1 AND 5),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Backs both the rating list (Requirement 7.4) and the average aggregate (Requirement 7.5).
CREATE INDEX idx_ratings_cake ON ratings (cake_id);
