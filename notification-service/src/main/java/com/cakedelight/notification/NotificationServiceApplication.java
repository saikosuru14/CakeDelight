package com.cakedelight.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Entry point for the Cake Delight Notification Service.
 *
 * <p>Owns the {@code notification_db} database, consumes the {@code order.completed} Kafka topic in
 * the {@code notification-service} consumer group, and serves the notification lookup endpoint under
 * {@code /api/notifications/orders/{orderId}} on port 8084.
 *
 * <p>{@link EnableRetry} activates the {@code @Retryable} interceptor used by
 * {@link com.cakedelight.notification.service.EmailChannel} for the transient-failure branch of
 * confirmation delivery. It is the only retry point in the service.
 */
@SpringBootApplication
@EnableRetry
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
