package com.cakedelight.notification.service;

/**
 * A delivery the channel will never accept, no matter how many times it is offered.
 *
 * <p>The only case in this increment is an event with no contact address: there is nothing to deliver
 * to, and a second attempt would fail on the same missing field. Retrying it would delay the
 * {@code FAILED} record without any chance of changing the outcome, so
 * {@link EmailChannel#send(com.cakedelight.notification.messaging.OrderCompletedEvent)} declares this
 * type as not retryable and it propagates on the first attempt.
 *
 * @see TransientDeliveryException the retryable counterpart
 */
public class PermanentDeliveryException extends RuntimeException {

    public PermanentDeliveryException(String message) {
        super(message);
    }
}
