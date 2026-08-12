package com.cakedelight.notification.dto;

import java.time.Instant;
import java.util.UUID;

import com.cakedelight.notification.domain.Notification;
import com.cakedelight.notification.domain.NotificationStatus;

/**
 * One stored order confirmation delivery attempt as returned by the API (Requirements 8.2, 8.5).
 *
 * <p>Keeps the JPA entity behind the HTTP boundary.
 *
 * @param id          identifier of the delivery attempt record
 * @param orderId     the order the confirmation was for
 * @param channel     the delivery channel used, {@code EMAIL} for this increment
 * @param status      the outcome of the attempt, {@code SENT} or {@code FAILED}
 * @param attemptedAt when the attempt was made
 */
public record NotificationResponse(
        UUID id, UUID orderId, String channel, NotificationStatus status, Instant attemptedAt) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getOrderId(),
                notification.getChannel(),
                notification.getStatus(),
                notification.getAttemptedAt());
    }
}
