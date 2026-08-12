package com.cakedelight.notification.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cakedelight.notification.domain.Notification;
import com.cakedelight.notification.domain.NotificationStatus;
import com.cakedelight.notification.messaging.OrderCompletedEvent;
import com.cakedelight.notification.repository.NotificationRepository;

/**
 * Sends order confirmations through the configured delivery channel and records one row per delivery
 * attempt (Requirements 8.1, 8.2, 8.3, 8.5).
 *
 * <h2>Every attempt is recorded, successful or not</h2>
 * {@link #deliver(OrderCompletedEvent)} always inserts a notification row with the order identifier,
 * the channel, the outcome, and the attempt timestamp (Requirement 8.2). A channel rejection is not an
 * error the caller has to handle: it is caught, logged at ERROR with the order identifier and the
 * failure reason, and stored as {@link NotificationStatus#FAILED} (Requirement 8.3). The exception is
 * deliberately not rethrown past that point, so a rejected confirmation still leaves an auditable
 * record instead of an unrecorded, redelivered message.
 *
 * <h2>Retry lives in the channel, not here</h2>
 * A {@code FAILED} record is the terminal state, and it stays terminal. Whether a failure deserved a
 * second attempt is a property of the channel, so {@link EmailChannel} owns that decision: it retries a
 * {@link TransientDeliveryException} within a small budget and never retries a
 * {@link PermanentDeliveryException} such as a missing contact address. By the time a failure reaches
 * the catch block below, the retries are already spent, so this method records exactly one row per
 * consumed event either way.
 *
 * <h2>What does propagate</h2>
 * The insert itself is not guarded. A {@code DataIntegrityViolationException} from the partial unique
 * index {@code uq_notifications_order_sent} means another delivery already recorded a {@code SENT} row
 * for this order, and that has to reach the listener, which owns the idempotency handling
 * (Requirements 8.4, 8.6). {@link #deliver(OrderCompletedEvent)} therefore runs without a surrounding
 * transaction: the repository save commits on its own, so the violation surfaces from this call rather
 * than later at an outer commit the caller cannot catch.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final EmailChannel emailChannel;

    public NotificationService(NotificationRepository notificationRepository, EmailChannel emailChannel) {
        this.notificationRepository = notificationRepository;
        this.emailChannel = emailChannel;
    }

    /**
     * Sends the order confirmation for one completed order and records the attempt
     * (Requirements 8.1, 8.2, 8.3).
     *
     * @param event the consumed payload, carrying the order identifier, the ordered items, the order
     *              total, and the contact details to deliver to
     * @return the stored record, {@code SENT} if the channel accepted the confirmation, {@code FAILED}
     *         if it rejected it
     */
    public Notification sendConfirmation(OrderCompletedEvent event) {
        NotificationStatus status;
        try {
            emailChannel.send(event);
            status = NotificationStatus.SENT;
        } catch (Exception failure) {
            // Requirement 8.3: the order identifier and the failure reason, at ERROR level. The stack
            // trace goes with it because a stub channel failing is worth diagnosing. Any retry the
            // channel was entitled to has already happened, so this is the terminal outcome.
            log.error("Order confirmation rejected by channel={} for orderId={} reason={}",
                    emailChannel.channel(), event.orderId(), failure.getMessage(), failure);
            status = NotificationStatus.FAILED;
        }

        // The caller logs the recorded attempt, so no INFO line is repeated here.
        return notificationRepository.save(new Notification(
                UUID.randomUUID(),
                event.orderId(),
                emailChannel.channel(),
                status,
                Instant.now()));
    }

    /**
     * Every stored delivery attempt for one order identifier, in whatever order the repository
     * returns them. Backs the notification lookup endpoint (Requirement 8.5); an order identifier with
     * no attempts yields an empty list rather than an error.
     */
    @Transactional(readOnly = true)
    public List<Notification> findByOrderId(UUID orderId) {
        return notificationRepository.findByOrderId(orderId);
    }
}
