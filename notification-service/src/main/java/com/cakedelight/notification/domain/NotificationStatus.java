package com.cakedelight.notification.domain;

/**
 * Outcome of a single order confirmation delivery attempt (Requirement 8.2).
 *
 * <p>Persisted as the readable name in the {@code notifications.status VARCHAR(20)} column via
 * {@code @Enumerated(EnumType.STRING)}. String mapping is mandatory, not stylistic: the partial
 * unique index {@code uq_notifications_order_sent} filters on {@code status = 'SENT'}, so an
 * ordinal mapping would store {@code 0} and the index would never match, silently dropping the
 * at-most-one-confirmation guarantee (Requirements 8.4, 8.6).
 */
public enum NotificationStatus {

    /**
     * The delivery channel accepted the confirmation. At most one such record exists per order
     * identifier, enforced by {@code uq_notifications_order_sent} (Requirement 8.6).
     */
    SENT,

    /**
     * The delivery channel rejected the confirmation (Requirement 8.3). Unconstrained: any number
     * of FAILED records may accumulate for one order identifier.
     */
    FAILED
}
