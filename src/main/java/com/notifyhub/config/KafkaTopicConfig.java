package com.notifyhub.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    @Value("${app.kafka.topics.high}")
    private String highTopic;

    @Value("${app.kafka.topics.medium}")
    private String mediumTopic;

    @Value("${app.kafka.topics.low}")
    private String lowTopic;

    @Value("${app.kafka.topics.dlq-email}")
    private String dlqEmailTopic;

    @Value("${app.kafka.topics.dlq-sms}")
    private String dlqSmsTopic;

    @Value("${app.kafka.topics.dlq-push}")
    private String dlqPushTopic;

    @Bean
    public NewTopic notificationHighTopic() {
        return TopicBuilder.name(highTopic).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic notificationMediumTopic() {
        return TopicBuilder.name(mediumTopic).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic notificationLowTopic() {
        return TopicBuilder.name(lowTopic).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic dlqEmailTopic() {
        return TopicBuilder.name(dlqEmailTopic).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic dlqSmsTopic() {
        return TopicBuilder.name(dlqSmsTopic).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic dlqPushTopic() {
        return TopicBuilder.name(dlqPushTopic).partitions(1).replicas(1).build();
    }
}
