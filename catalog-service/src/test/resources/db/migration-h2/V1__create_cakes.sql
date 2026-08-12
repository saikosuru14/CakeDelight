-- V1__create_cakes.sql  (H2 mirror - TEST ONLY)
--
-- Test-only mirror of src/main/resources/db/migration/V1__create_cakes.sql, used by the `test`
-- Spring profile (src/test/resources/application-test.yml). db/migration/ remains the source of
-- truth for PostgreSQL 16; nothing here ships in the application jar.
--
-- CHANGED FOR H2:
--   * The functional (expression) indexes `idx_cakes_category_lower ON cakes (lower(category))`
--     and `idx_cakes_name_lower ON cakes (lower(name))` become plain column indexes. H2 does not
--     support expression-based indexes, even in MODE=PostgreSQL. Behaviour is unaffected:
--     CakeRepository applies LOWER() to both column and parameter, so case-insensitive filtering
--     still returns the same rows on H2, it just cannot use an index for that predicate. Index
--     definitions are not inspected by `ddl-auto: validate`, so this divergence cannot mask
--     entity/schema drift.
--
-- Everything else is byte-for-byte equivalent to the PostgreSQL migration: same table, column
-- names, column order, types, nullability, DEFAULT and CHECK constraint, so `ddl-auto: validate`
-- still catches entity drift.
CREATE TABLE cakes (
    id           UUID PRIMARY KEY,
    name         VARCHAR(150)   NOT NULL,
    description  VARCHAR(1000),
    category     VARCHAR(50)    NOT NULL,
    price        NUMERIC(12,2)  NOT NULL CHECK (price >= 0),
    available    BOOLEAN        NOT NULL DEFAULT TRUE,
    image_url    VARCHAR(500)
);

CREATE INDEX idx_cakes_category ON cakes (category);
CREATE INDEX idx_cakes_name     ON cakes (name);
CREATE INDEX idx_cakes_price    ON cakes (price);
