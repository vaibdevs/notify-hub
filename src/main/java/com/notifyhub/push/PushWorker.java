package com.notifyhub.push;

import com.notifyhub.deliverylog.DeliveryLog;
import com.notifyhub.deliverylog.DeliveryLogRepository;
import com.notifyhub.deliverylog.DeliveryLogStatus;
import com.notifyhub.exception.DeliveryFailedException;
import com.notifyhub.metrics.NotificationMetrics;
import com.notifyhub.notification.Notification;
import com.notifyhub.notification.NotificationChannel;
import com.notifyhub.notification.NotificationRepository;
import com.notifyhub.notification.NotificationStatus;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PushWorker {

    private final PushService pushService;
    private final NotificationRepository notificationRepository;
    private final DeliveryLogRepository deliveryLogRepository;
    private final NotificationMetrics metrics;

    @KafkaListener(
            topics = {"${app.kafka.topics.high}",
                      "${app.kafka.topics.medium}",
                      "${app.kafka.topics.low}"},
            groupId = "${spring.kafka.consumer.group-id}-push",
            containerFactory = "kafkaListenerContainerFactory",
            properties = {"spring.json.value.default.type=com.notifyhub.notification.Notification"})
    public void onMessage(Notification notification) {
        if (notification == null || notification.getChannel() != NotificationChannel.PUSH) {
            return;
        }
        process(notification);
    }

    @Transactional
    public void process(Notification notification) {
        UUID id = notification.getId();
        markProcessing(id);
        long startNanos = System.nanoTime();
        int attemptNumber = nextAttemptNumber(id);
        String platform = inferPlatform(notification.getRecipientId());
        String provider = PushService.resolveProvider(platform);

        try {
            pushService.sendPush(notification, platform);
            long elapsedNanos = System.nanoTime() - startNanos;
            recordDelivered(notification, attemptNumber, provider, elapsedNanos);
        } catch (DeliveryFailedException ex) {
            recordFailure(notification, attemptNumber, provider, ex);
        } catch (CallNotPermittedException ex) {
            recordCircuitOpen(notification, attemptNumber, provider, ex);
        } catch (Exception ex) {
            log.error("Unexpected error delivering push notificationId={}", id, ex);
            recordFailure(notification, attemptNumber, provider,
                    new DeliveryFailedException("Unexpected delivery error", ex));
        }
    }

    private String inferPlatform(String recipientId) {
        if (recipientId == null) {
            return "android";
        }
        String lower = recipientId.toLowerCase();
        if (lower.startsWith("ios:") || lower.startsWith("apns:")) {
            return "ios";
        }
        return "android";
    }

    private void markProcessing(UUID id) {
        notificationRepository.findById(id).ifPresent(n -> {
            n.setStatus(NotificationStatus.PROCESSING);
            notificationRepository.save(n);
        });
    }

    private int nextAttemptNumber(UUID id) {
        Integer previous = deliveryLogRepository.findMaxAttemptNumber(id);
        return (previous == null ? 0 : previous) + 1;
    }

    private void recordDelivered(Notification notification, int attemptNumber, String provider, long elapsedNanos) {
        Notification managed = notificationRepository.findById(notification.getId())
                .orElse(notification);
        managed.setStatus(NotificationStatus.DELIVERED);
        managed.setDeliveredAt(LocalDateTime.now());
        notificationRepository.save(managed);

        deliveryLogRepository.save(DeliveryLog.builder()
                .notificationId(notification.getId())
                .attemptNumber(attemptNumber)
                .status(DeliveryLogStatus.DELIVERED)
                .provider(provider)
                .build());

        metrics.incrementDelivered(NotificationChannel.PUSH);
        metrics.recordDeliveryLatency(NotificationChannel.PUSH, elapsedNanos);
        log.info("Push delivered via {}: notificationId={} attempt={}",
                provider, notification.getId(), attemptNumber);
    }

    private void recordFailure(Notification notification, int attemptNumber, String provider, Throwable ex) {
        notificationRepository.findById(notification.getId()).ifPresent(n -> {
            n.setStatus(NotificationStatus.FAILED);
            notificationRepository.save(n);
        });

        deliveryLogRepository.save(DeliveryLog.builder()
                .notificationId(notification.getId())
                .attemptNumber(attemptNumber)
                .status(DeliveryLogStatus.FAILED)
                .provider(provider)
                .errorMessage(rootMessage(ex))
                .build());

        metrics.incrementFailed(NotificationChannel.PUSH);
        log.error("Push delivery failed: notificationId={} attempt={} reason={}",
                notification.getId(), attemptNumber, rootMessage(ex));
    }

    private void recordCircuitOpen(Notification notification, int attemptNumber, String provider, Throwable ex) {
        notificationRepository.findById(notification.getId()).ifPresent(n -> {
            n.setStatus(NotificationStatus.FAILED);
            notificationRepository.save(n);
        });

        deliveryLogRepository.save(DeliveryLog.builder()
                .notificationId(notification.getId())
                .attemptNumber(attemptNumber)
                .status(DeliveryLogStatus.CIRCUIT_OPEN)
                .provider(provider)
                .errorMessage(rootMessage(ex))
                .build());

        metrics.incrementFailed(NotificationChannel.PUSH);
        log.warn("Push circuit open — notificationId={} attempt={}", notification.getId(), attemptNumber);
    }

    private String rootMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
