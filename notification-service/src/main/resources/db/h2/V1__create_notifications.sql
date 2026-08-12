-- V1__create_notifications.sql  (H2 translation - `local` profile and test mirror)
--
-- H2-compatible translation of src/main/resources/db/migration/V1__create_notifications.sql. Run by
-- the `local` Spring profile from classpath:db/h2 (this copy, packaged in the jar) and mirrored for
-- tests at src/test/resources/db/migration-h2 for the `test` profile. db/migration/ remains the
-- source of truth for PostgreSQL 16, which stays the canonical production target; keep the copies in
-- sync by hand.
--
-- CHANGED FOR H2:
--   * `attempted_at` is spelled `TIMESTAMP WITH TIME ZONE` instead of the PostgreSQL-only alias
--     `TIMESTAMPTZ`. H2 does not recognise `TIMESTAMPTZ` as a type name, even in MODE=PostgreSQL.
--     Same SQL type, same semantics, same JDBC mapping.
--   * The PARTIAL unique index
--         CREATE UNIQUE INDEX uq_notifications_order_sent
--             ON notifications (order_id) WHERE status = 'SENT';
--     is replaced by a plain, NON-UNIQUE index on (order_id, status). H2 supports neither partial
--     indexes nor the expression index that would emulate one.
--
--     CONSEQUENCE, and it matters: on H2 the database does NOT reject a second SENT row for the
--     same order. The "at most one successful confirmation per order" guarantee (Requirements 8.4,
--     8.6) is therefore only enforced when running against PostgreSQL - it does not hold under the
--     `local` profile or the H2 test profile. Tests that assert the duplicate-SENT rejection or the
--     concurrent-listener race must run against PostgreSQL via Testcontainers, not against H2.
--     Application-level checks in the listener still behave the same on H2.
--
-- Everything else matches the PostgreSQL migration exactly: table name, column names, column
-- order, types and nullability, so `ddl-auto: validate` still catches entity drift. Index
-- definitions are not inspected by Hibernate validation, so the index change above cannot mask
-- schema drift.
--
-- Owns notification records only. order_id is a plain UUID column with no foreign key: orders
-- live in order_db and are owned by the Order Service, so no cross-database reference is
-- possible or permitted here (Requirement 10.1).
--
-- One row per delivery attempt (Requirement 8.2): a rejected attempt is recorded as FAILED and
-- a later attempt for the same order can still succeed, so multiple rows per order_id are normal.
CREATE TABLE notifications (
    id           UUID                     PRIMARY KEY,
    order_id     UUID                     NOT NULL,
    channel      VARCHAR(20)              NOT NULL,
    status       VARCHAR(20)              NOT NULL,
    attempted_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Stand-in for uq_notifications_order_sent: same columns are covered for lookups, but WITHOUT the
-- uniqueness constraint, for the reason explained above.
CREATE INDEX idx_notifications_order_status ON notifications (order_id, status);

-- Backs the notification lookup endpoint GET /api/notifications/orders/{orderId}
-- (Requirement 8.5) and the pre-send SENT check (Requirement 8.4).
CREATE INDEX idx_notifications_order ON notifications (order_id);
