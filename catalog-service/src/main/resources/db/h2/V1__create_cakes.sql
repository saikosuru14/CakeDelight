-- V1__create_cakes.sql  (H2 translation - `local` profile only)
--
-- H2-compatible translation of db/migration/V1__create_cakes.sql. PostgreSQL 16 remains the
-- canonical production target and db/migration stays authoritative; this copy exists only so the
-- service can run without a PostgreSQL instance. Keep the two in sync by hand: the entities run
-- with `ddl-auto: validate`, so any column drift fails startup, which is the point.
--
-- Table definition below is identical to the PostgreSQL version: same table name, column names,
-- column order, types, nullability, default and CHECK constraint.
CREATE TABLE cakes (
    id           UUID PRIMARY KEY,
    name         VARCHAR(150)   NOT NULL,
    description  VARCHAR(1000),
    category     VARCHAR(50)    NOT NULL,
    price        NUMERIC(12,2)  NOT NULL CHECK (price >= 0),
    available    BOOLEAN        NOT NULL DEFAULT TRUE,
    image_url    VARCHAR(500)
);

-- DIVERGENCE FROM POSTGRESQL: the PostgreSQL migration creates functional (expression) indexes,
-- `idx_cakes_category_lower ON cakes (lower(category))` and `idx_cakes_name_lower ON cakes
-- (lower(name))`. H2 does not support functional indexes, so they become plain column indexes
-- here. Query results are unaffected: CakeRepository.search applies LOWER() to both the column
-- and the parameter, so case-insensitive filtering still works on H2 - it just cannot use the
-- index for that predicate, which is irrelevant at local dataset sizes.
CREATE INDEX idx_cakes_category ON cakes (category);
CREATE INDEX idx_cakes_name     ON cakes (name);
CREATE INDEX idx_cakes_price    ON cakes (price);
