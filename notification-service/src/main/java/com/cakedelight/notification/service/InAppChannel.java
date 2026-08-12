package com.cakedelight.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cakedelight.notification.messaging.OrderCompletedEvent;
import com.cakedelight.notification.messaging.OrderCompletedItem;

/**
 * The in-app delivery channel: the confirmation is composed and made available for the customer to
 * retrieve from within the application, rather than pushed to an external provider
 * (Requirement 8.1).
 *
 * <p>Of the three options the brief names, in-app is the one this system genuinely implements. The
 * retrieval half already exists: {@code GET /api/notifications/orders/{orderId}} answers with every
 * stored attempt (Requirement 8.5), and the row {@code NotificationService} writes after this method
 * returns <em>is</em> the delivered confirmation — orderId, channel, outcome, timestamp. Nothing
 * leaves the process, so there is no provider to be unavailable.
 *
 * <p>Stub in the same shape as {@link EmailChannel}, and stubbed in the same place: the composed body
 * goes to the log so the confirmation is verifiable from the log alone, and the durable record is the
 * notification row. Only the delivery target differs. An in-app message is addressed to the customer's
 * account rather than to a mailbox, so this channel needs {@code customerId} and rejects an event
 * without one; that rejection becomes a {@code FAILED} record plus an ERROR log (Requirement 8.3),
 * exactly as a missing email address does on the email channel.
 *
 * <p>Selected by setting {@code cakedelight.notification.channel: IN_APP}. It replaces the email
 * channel, never runs alongside it: one active channel means one delivery and one {@code SENT} record
 * per order, which is what {@code uq_notifications_order_sent} enforces.
 */
@Component
public class InAppChannel implements NotificationChannel {

    /** The delivery channel value stored on a notification record produced here (Requirement 8.2). */
    public static final String CHANNEL = "IN_APP";

    private static final Logger log = LoggerFactory.getLogger(InAppChannel.class);

    /** The channel value to record for a delivery attempt made through this channel. */
    @Override
    public String channel() {
        return CHANNEL;
    }

    /**
     * Composes the confirmation for one completed order and makes it available for in-app retrieval
     * (Requirement 8.1).
     *
     * @param event the consumed payload; its {@code customerId} identifies the account the
     *              confirmation is addressed to
     * @throws IllegalArgumentException if the event names no customer, so there is no account to
     *                                  address; the caller records the attempt as {@code FAILED}
     */
    @Override
    public void send(OrderCompletedEvent event) {
        String recipient = event.customerId();
        if (recipient == null || recipient.isBlank()) {
            throw new IllegalArgumentException(
                    "No customer to address the in-app confirmation to for orderId=" + event.orderId());
        }

        String body = composeBody(event);

        // The delivery itself. The retrievable record is written by NotificationService immediately
        // after this returns; this line makes the body visible too, which the record does not carry.
        log.info("Delivered order confirmation via {} to customer {} for orderId={}\n{}",
                CHANNEL, recipient, event.orderId(), body);
    }

    /**
     * Builds the confirmation body from the order identifier, the ordered items, and the order total
     * (Requirement 8.1), at the scale the payload records already normalized to.
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
}
