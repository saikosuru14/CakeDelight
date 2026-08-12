-- Cake Delight - Rating Service schema (rating_db)
-- Owns ratings only. cake_id is a plain UUID column: cake data lives in catalog_db
-- and is owned by the Catalog Service, so no foreign key is possible here (Requirement 10.1).
-- The CHECK below is the database level guarantee behind the average-range invariant
-- (Requirement 7.7). Multiple ratings per customer per cake are allowed by design.
CREATE TABLE ratings (
    id          UUID PRIMARY KEY,
    cake_id     UUID         NOT NULL,
    customer_id VARCHAR(100) NOT NULL,
    score       INTEGER      NOT NULL CHECK (score BETWEEN 1 AND 5),
    created_at  TIMESTAMPTZ  NOT NULL
);

-- Backs both the rating list (Requirement 7.4) and the average aggregate (Requirement 7.5).
CREATE INDEX idx_ratings_cake ON ratings (cake_id);
