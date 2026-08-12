package com.cakedelight.order.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the {@code order.completed} topic the Order Service publishes to (Requirement 6.1).
 *
 * <p>One partition and replication factor 1: the local Docker Compose stack and the single-node
 * cluster used for the capstone run one broker, and ordering per order identifier is already
 * guaranteed by keying every record with that identifier. Spring Boot's auto-configured
 * {@code KafkaAdmin} creates the topic on startup when the broker allows it; a missing broker is
 * logged and does not stop the service.
 */
@Configuration
public class KafkaTopicConfig {

    /** Topic name, shared with the publisher and mirrored by the notification-service listener. */
    public static final String ORDER_COMPLETED_TOPIC = "order.completed";

    @Bean
    public NewTopic orderCompletedTopic() {
        return TopicBuilder.name(ORDER_COMPLETED_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
