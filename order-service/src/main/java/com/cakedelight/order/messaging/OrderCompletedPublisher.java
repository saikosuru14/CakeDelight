package com.cakedelight.order.messaging;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.cakedelight.order.config.KafkaTopicConfig;

/**
 * Publishes one {@link OrderCompletedEvent} to {@code order.completed} per committed checkout
 * (Requirement 6.1), keyed by the order identifier so every event for one order lands on the same
 * partition.
 *
 * <p>Publishing is triggered by the application event that {@code CheckoutService} raises inside its
 * transaction, delivered here only in the {@link TransactionPhase#AFTER_COMMIT} phase. A rolled back
 * checkout therefore publishes nothing (Requirement 6.3). {@code fallbackExecution = true} keeps the
 * hand-off working when a caller raises the event outside a transaction.
 *
 * <p>A broker failure must not fail the checkout that already committed: {@link
 * #publish(OrderCompletedEvent)} catches both the synchronous send failure and the asynchronous
 * completion failure, logs at ERROR with the order identifier, and returns normally, so the checkout
 * still answers 201 (Requirement 6.4). Nothing is retried and nothing is stored for replay; that is
 * out of scope for this increment.
 */
@Component
public class OrderCompletedPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderCompletedPublisher.class);

    /** Single ERROR message form for both the synchronous and the asynchronous failure path. */
    private static final String PUBLISH_FAILED = "Failed to publish order.completed for orderId={}";

    private final KafkaTemplate<String, OrderCompletedEvent> kafkaTemplate;

    public OrderCompletedPublisher(KafkaTemplate<String, OrderCompletedEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /** Receives the checkout hand-off after the order transaction commits. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderCompleted(OrderCompletedEvent event) {
        publish(event);
    }

    /**
     * Sends the event and swallows every failure. Never throws, whatever the broker does.
     *
     * @param event the payload; its order identifier becomes the record key
     */
    public void publish(OrderCompletedEvent event) {
        String key = event.orderId() == null ? null : event.orderId().toString();
        try {
            CompletableFuture<SendResult<String, OrderCompletedEvent>> sent =
                    kafkaTemplate.send(KafkaTopicConfig.ORDER_COMPLETED_TOPIC, key, event);
            if (sent != null) {
                // send() only queues the record, so the broker failure usually arrives here.
                sent.whenComplete((result, failure) -> {
                    if (failure != null) {
                        log.error(PUBLISH_FAILED, key, failure);
                    }
                });
            }
        } catch (Exception failure) {
            // Buffer exhaustion, serialization failure, or an unreachable broker at send time.
            log.error(PUBLISH_FAILED, key, failure);
        }
    }
}
