package com.cakedelight.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.RetryContext;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.stereotype.Component;

import com.cakedelight.notification.messaging.OrderCompletedEvent;
import com.cakedelight.notification.messaging.OrderCompletedItem;

/**
 * The default delivery channel of this service: a logging stub that composes the order confirmation
 * and writes it to the log instead of talking to an SMTP server (Requirement 8.1).
 *
 * <p>Stub by design, not by omission. The capstone demonstrates the checkout to notification flow, so
 * no mail server is required to run it; the composed body and the recipient appear in the service log,
 * which is what makes the delivery observable. {@link #CHANNEL} is the value stored on a notification
 * record produced through this channel (Requirement 8.2).
 *
 * <p>One of the {@link NotificationChannel} implementations, and the one selected when
 * {@code cakedelight.notification.channel} is absent or set to {@code EMAIL}. Selecting
 * {@link InAppChannel} instead swaps the delivery route; it never adds a second one, so there is still
 * no multi-channel fan-out and at most one {@code SENT} record per order.
 *
 * <h2>Fault tolerance: two failure kinds, only one of them retried</h2>
 *
 * <p>{@link #send(OrderCompletedEvent)} sorts every failure into one of two types before deciding
 * anything:
 *
 * <ul>
 *   <li>{@link PermanentDeliveryException} — the event carries no contact address. The confirmation is
 *       undeliverable and always will be, so it is <em>never</em> retried and propagates on the first
 *       attempt.</li>
 *   <li>{@link TransientDeliveryException} — the channel was momentarily unusable. This is the only
 *       retried type: a temporary failure gets a second chance before the attempt is written off.</li>
 * </ul>
 *
 * <p>The budget is deliberately tiny: 2 total attempts with a 200 ms backoff, configurable under
 * {@code notification.delivery.retry.*}. The listener is a Kafka consumer, so time spent retrying is
 * time the partition is not being drained; a small bound keeps a sick channel from stalling the
 * consumer. When the budget runs out, {@link #recoverExhausted(TransientDeliveryException,
 * OrderCompletedEvent)} rethrows, and {@code NotificationService} records the terminal {@code FAILED}
 * attempt and logs it at ERROR — the behaviour that existed before retries, unchanged.
 */
@Component
public class EmailChannel implements NotificationChannel {

    /** The delivery channel value stored on every notification record (Requirement 8.2). */
    public static final String CHANNEL = "EMAIL";

    private static final Logger log = LoggerFactory.getLogger(EmailChannel.class);

    /** Reported in the give-up log line; the retry interceptor enforces the value itself. */
    private final int maxAttempts;

    public EmailChannel(
            @Value("${notification.delivery.retry.max-attempts:2}") int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    /** The channel value to record for a delivery attempt made through this channel. */
    @Override
    public String channel() {
        return CHANNEL;
    }

    /**
     * Composes the confirmation for one completed order and delivers it to the contact details carried
     * in the event (Requirement 8.1), retrying only a transient channel failure.
     *
     * @param event the consumed payload; its {@code customerEmail} is the recipient
     * @throws PermanentDeliveryException if the event carries no contact address, so there is nothing
     *                                    to deliver to; never retried, and the caller records the
     *                                    attempt as {@code FAILED}
     * @throws TransientDeliveryException if the channel failed on every allowed attempt; the caller
     *                                    records the attempt as {@code FAILED}
     */
    @Retryable(
            retryFor = TransientDeliveryException.class,
            noRetryFor = PermanentDeliveryException.class,
            maxAttemptsExpression = "${notification.delivery.retry.max-attempts:2}",
            backoff = @Backoff(delayExpression = "${notification.delivery.retry.backoff-ms:200}"))
    @Override
    public void send(OrderCompletedEvent event) {
        String recipient = event.customerEmail();
        if (recipient == null || recipient.isBlank()) {
            // Deterministic: no address means no delivery, on this attempt or any later one.
            throw new PermanentDeliveryException(
                    "No contact details on the event for orderId=" + event.orderId());
        }

        try {
            deliver(recipient, event);
        } catch (RuntimeException failure) {
            // The channel misbehaved rather than the confirmation being undeliverable. Worth one more
            // attempt, so it is classified transient.
            log.warn("Order confirmation delivery to {} for orderId={} failed on attempt {} of {}: {}",
                    recipient, event.orderId(), currentAttempt(), maxAttempts, failure.getMessage());
            throw new TransientDeliveryException(
                    "Channel " + CHANNEL + " could not deliver orderId=" + event.orderId(), failure);
        }
    }

    /**
     * Called once the attempt budget is exhausted. Rethrows so the terminal state stays what it always
     * was: {@code NotificationService} records a {@code FAILED} attempt and logs it at ERROR
     * (Requirement 8.3).
     *
     * @param exception the failure from the final attempt
     * @param event     the event that could not be delivered
     */
    @Recover
    public void recoverExhausted(TransientDeliveryException exception, OrderCompletedEvent event) {
        log.error("Giving up on channel {} for orderId={} after {} attempt(s)",
                CHANNEL, event.orderId(), maxAttempts);
        throw exception;
    }

    /**
     * The delivery itself: recipient plus the full body, so the confirmation is verifiable from the log
     * alone.
     */
    private void deliver(String recipient, OrderCompletedEvent event) {
        String body = composeBody(event);
        log.info("Sent order confirmation via {} to {} for orderId={}\n{}",
                CHANNEL, recipient, event.orderId(), body);
    }

    /**
     * Builds the confirmation body from the order identifier, the ordered items, and the order total
     * (Requirement 8.1). Money is rendered at the scale the payload records already normalized to, so
     * the body never shows a different total than the one that was published.
     */
    String composeBody(OrderCompletedEvent event) {
        StringBuilder body = new StringBuilder()
                .append("Order confirmation\n")
                .append("Order: ").append(event.orderId()).append('\n')
                .append("Items:\n");
        for (OrderCompletedItem item : event.items()) {
            body.append("  - ").append(item.cakeName())
                    .append(" x").append(item.quantity())
                    .append(" @ ").append(item.unitPrice())
                    .append(" = ").append(item.lineTotal())
                    .append('\n');
        }
        return body.append("Order total: ").append(event.orderTotal()).toString();
    }

    /**
     * The 1-based number of the attempt currently running. The retry count is incremented only after
     * the interceptor registers the failure, which happens after this method returns, so the running
     * attempt is the recorded count plus one. Falls back to 1 outside the retry interceptor, for
     * example in a direct unit test.
     */
    private static int currentAttempt() {
        RetryContext context = RetrySynchronizationManager.getContext();
        return context == null ? 1 : context.getRetryCount() + 1;
    }
}
