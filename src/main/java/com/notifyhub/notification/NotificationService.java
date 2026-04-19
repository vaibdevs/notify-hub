package com.notifyhub.notification;

import com.notifyhub.deliverylog.DeliveryLogRepository;
import com.notifyhub.exception.ResourceNotFoundException;
import com.notifyhub.metrics.NotificationMetrics;
import com.notifyhub.notification.dto.NotificationRequest;
import com.notifyhub.notification.dto.NotificationResponse;
import com.notifyhub.ratelimit.RateLimiterService;
import com.notifyhub.template.TemplateEngine;
import com.notifyhub.tenant.Tenant;
import com.notifyhub.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final DeliveryLogRepository deliveryLogRepository;
    private final TenantRepository tenantRepository;
    private final RateLimiterService rateLimiterService;
    private final TemplateEngine templateEngine;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final NotificationMetrics metrics;

    @Value("${app.kafka.topics.high}")
    private String highTopic;
    @Value("${app.kafka.topics.medium}")
    private String mediumTopic;
    @Value("${app.kafka.topics.low}")
    private String lowTopic;

    @Transactional
    public NotificationResponse submit(NotificationRequest request) {
        Tenant tenant = tenantRepository.findByTenantId(request.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Tenant not found or inactive: " + request.getTenantId()));
        if (!Boolean.TRUE.equals(tenant.getActive())) {
            throw new ResourceNotFoundException(
                    "Tenant not found or inactive: " + request.getTenantId());
        }

        rateLimiterService.checkRateLimit(request.getTenantId());

        TemplateEngine.RenderedTemplate rendered = templateEngine.render(
                request.getTemplateId(), request.getChannel(), request.getTemplateData());

        NotificationPriority priority = request.getPriority() == null
                ? NotificationPriority.MEDIUM
                : request.getPriority();

        Notification notification = Notification.builder()
                .tenantId(request.getTenantId())
                .recipientId(request.getRecipientId())
                .channel(request.getChannel())
                .priority(priority)
                .templateId(request.getTemplateId())
                .content(renderPayload(rendered))
                .status(NotificationStatus.QUEUED)
                .build();

        Notification saved = notificationRepository.save(notification);
        log.info("Notification queued: id={} tenant={} channel={} priority={}",
                saved.getId(), saved.getTenantId(), saved.getChannel(), saved.getPriority());

        String topic = topicFor(priority);
        kafkaTemplate.send(topic, saved.getId().toString(), saved);
        metrics.incrementSent(saved.getChannel());

        return NotificationResponse.builder()
                .notificationId(saved.getId())
                .status(saved.getStatus())
                .channel(saved.getChannel())
                .createdAt(saved.getCreatedAt())
                .build();
    }

    @Transactional(readOnly = true)
    public NotificationResponse getStatus(UUID id) {
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notification", id));
        Integer attempts = deliveryLogRepository.findMaxAttemptNumber(id);
        return NotificationResponse.builder()
                .notificationId(notification.getId())
                .status(notification.getStatus())
                .channel(notification.getChannel())
                .attempts(attempts == null ? 0 : attempts)
                .createdAt(notification.getCreatedAt())
                .deliveredAt(notification.getDeliveredAt())
                .build();
    }

    @Transactional(readOnly = true)
    public Notification getNotification(UUID id) {
        return notificationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notification", id));
    }

    public String topicFor(NotificationPriority priority) {
        return switch (priority) {
            case HIGH -> highTopic;
            case MEDIUM -> mediumTopic;
            case LOW -> lowTopic;
        };
    }

    private String renderPayload(TemplateEngine.RenderedTemplate rendered) {
        if (rendered.subject() == null || rendered.subject().isBlank()) {
            return rendered.body();
        }
        return rendered.subject() + "\n" + rendered.body();
    }
}
