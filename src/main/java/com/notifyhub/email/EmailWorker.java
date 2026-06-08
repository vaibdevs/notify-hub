package com.notifyhub.email;

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
public class EmailWorker {

    private final EmailService emailService;
    private final NotificationRepository notificationRepository;
    private final DeliveryLogRepository deliveryLogRepository;
    private final NotificationMetrics metrics;

    @KafkaListener(
            topics = {"${app.kafka.topics.high}",
                      "${app.kafka.topics.medium}",
                      "${app.kafka.topics.low}"},
            groupId = "${spring.kafka.consumer.group-id}-email",
            containerFactory = "kafkaListenerContainerFactory",
            properties = {"spring.json.value.default.type=com.notifyhub.notification.Notification"})
    public void onMessage(Notification notification) {
        if (notification == null || notification.getChannel() != NotificationChannel.EMAIL) {
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

        try {
            emailService.sendEmail(notification);
            long elapsedNanos = System.nanoTime() - startNanos;
            recordDelivered(notification, attemptNumber, elapsedNanos);
        } catch (DeliveryFailedException ex) {
            recordFailure(notification, attemptNumber, ex);
        } catch (CallNotPermittedException ex) {
            recordCircuitOpen(notification, attemptNumber, ex);
        } catch (Exception ex) {
            log.error("Unexpected error delivering email notificationId={}", id, ex);
            recordFailure(notification, attemptNumber,
                    new DeliveryFailedException("Unexpected delivery error", ex));
        }
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

    private void recordDelivered(Notification notification, int attemptNumber, long elapsedNanos) {
        Notification managed = notificationRepository.findById(notification.getId())
                .orElse(notification);
        managed.setStatus(NotificationStatus.DELIVERED);
        managed.setDeliveredAt(LocalDateTime.now());
        notificationRepository.save(managed);

        deliveryLogRepository.save(DeliveryLog.builder()
                .notificationId(notification.getId())
                .attemptNumber(attemptNumber)
                .status(DeliveryLogStatus.DELIVERED)
                .provider(EmailService.PROVIDER)
                .build());

        metrics.incrementDelivered(NotificationChannel.EMAIL);
        metrics.recordDeliveryLatency(NotificationChannel.EMAIL, elapsedNanos);
        log.info("Email delivered: notificationId={} attempt={}", notification.getId(), attemptNumber);
    }

    private void recordFailure(Notification notification, int attemptNumber, Throwable ex) {
        notificationRepository.findById(notification.getId()).ifPresent(n -> {
            n.setStatus(NotificationStatus.FAILED);
            notificationRepository.save(n);
        });

        deliveryLogRepository.save(DeliveryLog.builder()
                .notificationId(notification.getId())
                .attemptNumber(attemptNumber)
                .status(DeliveryLogStatus.FAILED)
                .provider(EmailService.PROVIDER)
                .errorMessage(rootMessage(ex))
                .build());

        metrics.incrementFailed(NotificationChannel.EMAIL);
        log.error("Email delivery failed: notificationId={} attempt={} reason={}",
                notification.getId(), attemptNumber, rootMessage(ex));
    }

    private void recordCircuitOpen(Notification notification, int attemptNumber, Throwable ex) {
        notificationRepository.findById(notification.getId()).ifPresent(n -> {
            n.setStatus(NotificationStatus.FAILED);
            notificationRepository.save(n);
        });

        deliveryLogRepository.save(DeliveryLog.builder()
                .notificationId(notification.getId())
                .attemptNumber(attemptNumber)
                .status(DeliveryLogStatus.CIRCUIT_OPEN)
                .provider(EmailService.PROVIDER)
                .errorMessage(rootMessage(ex))
                .build());

        metrics.incrementFailed(NotificationChannel.EMAIL);
        log.warn("Email circuit open — notificationId={} attempt={}", notification.getId(), attemptNumber);
    }

    private String rootMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
