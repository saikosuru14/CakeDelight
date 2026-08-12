package com.cakedelight.notification.service;

import com.cakedelight.notification.messaging.OrderCompletedEvent;

/**
 * One way of delivering an order confirmation (Requirement 8.1). The capstone brief names three
 * options — "email, SMS, or in-app notification" — and this interface is the seam that makes the
 * choice explicit: {@link EmailChannel} and {@link InAppChannel} implement it, and
 * {@link NotificationService} resolves exactly one of them from {@code cakedelight.notification.channel}.
 *
 * <h2>One active channel, not a fan-out</h2>
 * The brief's list is a choice, not a mandate to send the same confirmation three times. Fan-out
 * would also break the delivery guarantee: the partial unique index {@code uq_notifications_order_sent}
 * constrains {@code (order_id) WHERE status = 'SENT'} — one SENT row per <em>order</em>, not per
 * channel — so three channels delivering one confirmation would mean three SENT rows for one order and
 * the second insert would be rejected. The configured channel is therefore singular, and
 * {@link #channel()} is the value recorded on the attempt so the delivery route is auditable.
 *
 * <h2>Contract for an implementation</h2>
 * <ul>
 *   <li>{@link #channel()} returns a stable, short, upper-case name. It is stored in
 *       {@code notifications.channel VARCHAR(20)} and is the value matched against the configured
 *       property, so it must be unique across implementations and must not change once records exist.
 *   <li>{@link #send(OrderCompletedEvent)} either delivers or throws. Throwing is the documented way
 *       to reject a confirmation that cannot be delivered — {@link NotificationService} catches it,
 *       logs at ERROR with the order identifier and reason, and records the attempt as
 *       {@code FAILED} (Requirement 8.3). An implementation must never swallow a failure and return
 *       normally, because that would record a {@code SENT} row for a confirmation nobody received.
 *   <li>Implementations are Spring beans; every bean of this type is offered to
 *       {@link NotificationService}, which selects one and ignores the rest.
 * </ul>
 *
 * <h2>The third option: SMS is deliberately absent</h2>
 * There is no {@code SmsChannel}, and its absence is a decision rather than an oversight. An SMS
 * implementation cannot be written honestly against the current contract: {@link OrderCompletedEvent}
 * carries {@code customerEmail} and no phone number, so a stub would have nothing to address. Adding
 * it needs, in this order:
 *
 * <ol>
 *   <li>A recipient phone number on the event contract — say {@code customerPhone}. That is a
 *       coordinated change across three places, because there is no shared library: the publisher's
 *       copy of the record in {@code order-service} ({@code com.cakedelight.order.messaging.OrderCompletedEvent}),
 *       the consumer's copy here ({@code com.cakedelight.notification.messaging.OrderCompletedEvent}),
 *       and {@code docs/event-contract.md}, which is the authority both copies answer to. Adding a
 *       field is backward compatible on the wire (unknown properties are ignored on read), so
 *       publisher and consumer can be rolled out independently; renaming or removing one is not.
 *       Checkout would also need to collect and validate the number.
 *   <li>A provider integration — an SMS gateway client plus its credentials, supplied through the
 *       environment like every other secret in this project, never {@code application.yml}.
 *   <li>An {@code SmsChannel} implementing this interface with {@code channel()} returning
 *       {@code "SMS"}, rejecting a null or blank phone number the same way {@link EmailChannel}
 *       rejects a missing address. No other code changes: {@link NotificationService} would find it
 *       by name once {@code cakedelight.notification.channel: SMS} is set.
 * </ol>
 *
 * Until a provider and a phone number exist, an {@code SmsChannel} would be fiction that logs a
 * confirmation to nowhere while recording it as {@code SENT}.
 */
public interface NotificationChannel {

    /**
     * The stable channel name recorded on every delivery attempt made through this channel
     * (Requirement 8.2), and the value {@code cakedelight.notification.channel} is matched against.
     */
    String channel();

    /**
     * Delivers the order confirmation for one completed order (Requirement 8.1).
     *
     * @param event the consumed payload, carrying the order identifier, the ordered items, the order
     *              total, and the customer details to address the confirmation to
     * @throws RuntimeException if the confirmation cannot be delivered; the caller records the attempt
     *                          as {@code FAILED} and logs the reason (Requirement 8.3)
     */
    void send(OrderCompletedEvent event);
}
