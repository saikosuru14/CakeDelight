package com.cakedelight.order.domain;

/**
 * Lifecycle of an {@link Order}. The only permitted transition is {@code CREATED -> CONFIRMED},
 * driven by an explicit confirmation endpoint (Requirement 6.5).
 *
 * <p>Persisted as the enum name in {@code orders.status VARCHAR(20)} via
 * {@code @Enumerated(EnumType.STRING)}, so the stored value stays readable.
 */
public enum OrderStatus {

    /** Assigned at checkout (Requirement 5.1). */
    CREATED,

    /** Assigned when the order confirmation endpoint is called (Requirement 6.5). */
    CONFIRMED
}
