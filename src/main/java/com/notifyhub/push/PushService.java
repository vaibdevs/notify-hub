package com.notifyhub.push;

import com.notifyhub.dlq.DlqService;
import com.notifyhub.exception.DeliveryFailedException;
import com.notifyhub.notification.Notification;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class PushService {

    public static final String PROVIDER_FCM = "fcm";
    public static final String PROVIDER_APNS = "apns";

    private final DlqService dlqService;

    @Value("${app.providers.push.fcm-server-key}")
    private String fcmServerKey;

    @Value("${app.providers.push.apns-key}")
    private String apnsKey;

    @Value("${app.providers.push.simulated-failure-rate:0.0}")
    private double simulatedFailureRate;

    @Value("${app.providers.push.simulated-latency-ms:0}")
    private long simulatedLatencyMs;

    @CircuitBreaker(name = "pushProvider", fallbackMethod = "pushFallback")
    @Retry(name = "pushProvider")
    public String sendPush(Notification notification, String platform) {
        String provider = resolveProvider(platform);
        log.info("{} call: keyPrefix={} to={} notificationId={}",
                provider.toUpperCase(), keyPrefix(provider),
                notification.getRecipientId(), notification.getId());

        simulateLatency();

        if (ThreadLocalRandom.current().nextDouble() < simulatedFailureRate) {
            throw new IllegalStateException("Simulated " + provider + " 5xx error");
        }

        log.info("{} delivered push: notificationId={}", provider.toUpperCase(), notification.getId());
        return provider;
    }

    @SuppressWarnings("unused")
    private String pushFallback(Notification notification, String platform, CallNotPermittedException ex) {
        log.warn("Push circuit OPEN — publishing to DLQ for notificationId={}", notification.getId());
        dlqService.publishToDlq(notification, "Circuit open: " + ex.getMessage(), 0);
        throw new DeliveryFailedException("Push circuit open", ex);
    }

    @SuppressWarnings("unused")
    private String pushFallback(Notification notification, String platform, Throwable ex) {
        log.error("Push delivery failed after retries — publishing to DLQ notificationId={}",
                notification.getId(), ex);
        dlqService.publishToDlq(notification, "Retries exhausted: " + ex.getMessage(), 0);
        throw new DeliveryFailedException("Push retries exhausted", ex);
    }

    public static String resolveProvider(String platform) {
        if (platform == null) {
            return PROVIDER_FCM;
        }
        String normalized = platform.trim().toLowerCase();
        return switch (normalized) {
            case "ios", "apns" -> PROVIDER_APNS;
            default -> PROVIDER_FCM;
        };
    }

    private void simulateLatency() {
        if (simulatedLatencyMs <= 0) {
            return;
        }
        try {
            Thread.sleep(simulatedLatencyMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private String keyPrefix(String provider) {
        String raw = PROVIDER_APNS.equals(provider) ? apnsKey : fcmServerKey;
        if (raw == null || raw.length() < 4) {
            return "****";
        }
        return raw.substring(0, 4) + "****";
    }
}
