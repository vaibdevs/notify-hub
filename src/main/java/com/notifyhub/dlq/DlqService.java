package com.notifyhub.dlq;

import com.notifyhub.notification.Notification;
import com.notifyhub.notification.NotificationChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class DlqService {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final DlqRepository dlqRepository;

    @Value("${app.kafka.topics.dlq-email}")
    private String dlqEmailTopic;
    @Value("${app.kafka.topics.dlq-sms}")
    private String dlqSmsTopic;
    @Value("${app.kafka.topics.dlq-push}")
    private String dlqPushTopic;

    public void publishToDlq(Notification notification, String reason, int attemptCount) {
        DlqMessage message = DlqMessage.builder()
                .notificationId(notification.getId())
                .tenantId(notification.getTenantId())
                .recipientId(notification.getRecipientId())
                .channel(notification.getChannel())
                .priority(notification.getPriority())
                .templateId(notification.getTemplateId())
                .content(notification.getContent())
                .reason(reason)
                .attemptCount(attemptCount)
                .failedAt(LocalDateTime.now())
                .build();

        String topic = topicFor(notification.getChannel());
        kafkaTemplate.send(topic, message.getNotificationId().toString(), message);
        log.warn("DLQ published: topic={} notificationId={} reason={}",
                topic, message.getNotificationId(), reason);
    }

    @KafkaListener(topics = "${app.kafka.topics.dlq-email}",
            containerFactory = "kafkaListenerContainerFactory",
            properties = {"spring.json.value.default.type=com.notifyhub.dlq.DlqMessage"})
    public void onEmailDlq(DlqMessage message) {
        dlqRepository.save(message);
        log.info("DLQ[email] stored: id={} reason={}", message.getNotificationId(), message.getReason());
    }

    @KafkaListener(topics = "${app.kafka.topics.dlq-sms}",
            containerFactory = "kafkaListenerContainerFactory",
            properties = {"spring.json.value.default.type=com.notifyhub.dlq.DlqMessage"})
    public void onSmsDlq(DlqMessage message) {
        dlqRepository.save(message);
        log.info("DLQ[sms] stored: id={} reason={}", message.getNotificationId(), message.getReason());
    }

    @KafkaListener(topics = "${app.kafka.topics.dlq-push}",
            containerFactory = "kafkaListenerContainerFactory",
            properties = {"spring.json.value.default.type=com.notifyhub.dlq.DlqMessage"})
    public void onPushDlq(DlqMessage message) {
        dlqRepository.save(message);
        log.info("DLQ[push] stored: id={} reason={}", message.getNotificationId(), message.getReason());
    }

    private String topicFor(NotificationChannel channel) {
        return switch (channel) {
            case EMAIL -> dlqEmailTopic;
            case SMS -> dlqSmsTopic;
            case PUSH -> dlqPushTopic;
        };
    }
}
