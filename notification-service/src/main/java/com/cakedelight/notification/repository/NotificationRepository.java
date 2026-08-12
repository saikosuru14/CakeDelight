package com.cakedelight.notification.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cakedelight.notification.domain.Notification;
import com.cakedelight.notification.domain.NotificationStatus;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Whether a delivery attempt with the given outcome is already recorded for an order identifier.
     *
     * <p>Called with {@link NotificationStatus#SENT} before sending, so a repeated
     * {@code order.completed} event is skipped instead of producing a second confirmation
     * (Requirement 8.4). This is the cheap pre-send check only; the actual guarantee lives in the
     * partial unique index {@code uq_notifications_order_sent}, which also covers two events for
     * one order being processed concurrently (Requirement 8.6).
     */
    boolean existsByOrderIdAndStatus(UUID orderId, NotificationStatus status);

    /**
     * Every stored delivery attempt for one order identifier, backing
     * {@code GET /api/notifications/orders/{orderId}} (Requirement 8.5).
     *
     * <p>Returns a list rather than a single record because FAILED attempts accumulate
     * (Requirement 8.3). An order identifier with no attempts yields an empty list.
     */
    List<Notification> findByOrderId(UUID orderId);
}
