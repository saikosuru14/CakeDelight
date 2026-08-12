-- Cake Delight - Notification Service schema (notification_db)
--
-- Owns notification records only. order_id is a plain UUID column with no foreign key: orders
-- live in order_db and are owned by the Order Service, so no cross-database reference is
-- possible or permitted here (Requirement 10.1).
--
-- One row per delivery attempt (Requirement 8.2): a rejected attempt is recorded as FAILED and
-- a later attempt for the same order can still succeed, so multiple rows per order_id are normal.
CREATE TABLE notifications (
    id           UUID         PRIMARY KEY,
    order_id     UUID         NOT NULL,
    channel      VARCHAR(20)  NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    attempted_at TIMESTAMPTZ  NOT NULL
);

-- The database level guarantee behind "at most one successful confirmation per order"
-- (Requirements 8.4, 8.6). A PARTIAL unique index: it constrains only the rows where
-- status = 'SENT', so any number of FAILED attempts for one order_id is allowed while a second
-- SENT row for the same order_id is rejected. Two listener invocations racing on the same event
-- therefore cannot both insert a SENT record; the loser gets a unique violation, which
-- OrderCompletedListener catches and logs.
--
-- status is compared as the literal string 'SENT', which is why the entity maps
-- NotificationStatus with @Enumerated(EnumType.STRING). Ordinal mapping would silently break
-- this index.
CREATE UNIQUE INDEX uq_notifications_order_sent
    ON notifications (order_id) WHERE status = 'SENT';

-- Backs the notification lookup endpoint GET /api/notifications/orders/{orderId}
-- (Requirement 8.5) and the pre-send SENT check (Requirement 8.4).
CREATE INDEX idx_notifications_order ON notifications (order_id);
