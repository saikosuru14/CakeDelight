package com.cakedelight.notification.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.cakedelight.notification.domain.Notification;
import com.cakedelight.notification.domain.NotificationStatus;
import com.cakedelight.notification.repository.NotificationRepository;
import com.cakedelight.notification.service.NotificationService;

/**
 * Consumes {@code order.completed} and turns each event into at most one successful order
 * confirmation (Requirements 8.1, 8.2, 8.4, 8.6).
 *
 * <p>Idempotency has two layers, and both are needed:
 *
 * <ol>
 *   <li>The pre-send check: an order that already has a SENT record is skipped before the channel is
 *       touched, so an ordinary redelivery produces neither a second confirmation nor a second record
 *       (Requirement 8.4). The check has to happen here rather than inside
 *       {@link NotificationService#sendConfirmation}, because skipping means "do not deliver", not
 *       merely "do not insert".
 *   <li>The partial unique index {@code uq_notifications_order_sent}: two events for one order
 *       processed concurrently can both pass the check, and the database rejects the losing insert
 *       (Requirement 8.6).
 * </ol>
 *
 * <p>Delivery resilience sits one level down, in
 * {@link com.cakedelight.notification.service.EmailChannel}: a transient channel failure is retried
 * within a small bounded budget, a permanent rejection such as a missing contact address is not. Either
 * way the outcome that reaches this listener is already terminal, which is why the retry is invisible
 * here — the listener still sees exactly one recorded attempt per consumed event.
 *
 * <p>Scope boundary: no dead-letter topic, no unbounded redelivery, no exactly-once consumption. Skip,
 * send, record, and swallow the duplicate violation. The listener never propagates an exception for a
 * case the guarantee already covers, since escaping the listener would only earn a redelivery of an
 * event that has nothing left to do.
 */
@Component
public class OrderCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderCompletedListener.class);

    private final NotificationRepository notificationRepository;
    private final NotificationService notificationService;

    public OrderCompletedListener(NotificationRepository notificationRepository,
            NotificationService notificationService) {
        this.notificationRepository = notificationRepository;
        this.notificationService = notificationService;
    }

    /**
     * Handles one {@code order.completed} event.
     *
     * <p>The {@code groupId} restates {@code spring.kafka.consumer.group-id} from
     * {@code application.yml}; both name {@code notification-service}, the consumer group fixed by the
     * event contract.
     */
    @KafkaListener(topics = "order.completed", groupId = "notification-service")
    public void onOrderCompleted(OrderCompletedEvent event) {
        if (notificationRepository.existsByOrderIdAndStatus(event.orderId(), NotificationStatus.SENT)) {
            log.info("Order {} already has a sent confirmation, skipping this order.completed event",
                    event.orderId());
            return;
        }
        try {
            Notification attempt = notificationService.sendConfirmation(event);
            log.info("Recorded {} confirmation attempt {} for order {} on channel {}",
                    attempt.getStatus(), attempt.getId(), attempt.getOrderId(), attempt.getChannel());
        } catch (DataIntegrityViolationException duplicate) {
            // The guarantee working as designed, not a fault: a concurrent delivery won the race and
            // this order already has its one successful confirmation (Requirement 8.6).
            log.warn("Concurrent confirmation already sent for order {}, discarding duplicate: {}",
                    event.orderId(), duplicate.getMessage());
        }
    }
}
