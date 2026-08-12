package com.cakedelight.notification.service;

/**
 * A delivery failure that may succeed if it is offered again: the channel itself was momentarily
 * unusable rather than the confirmation being undeliverable.
 *
 * <p>This is the only failure type
 * {@link EmailChannel#send(com.cakedelight.notification.messaging.OrderCompletedEvent)} retries. When
 * the small attempt budget is exhausted it still surfaces to {@code NotificationService}, which records
 * the terminal {@code FAILED} attempt and logs it at ERROR exactly as before.
 *
 * @see PermanentDeliveryException the non-retryable counterpart
 */
public class TransientDeliveryException extends RuntimeException {

    public TransientDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
