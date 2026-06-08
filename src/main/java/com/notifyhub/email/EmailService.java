package com.notifyhub.email;

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
public class EmailService {

    public static final String PROVIDER = "sendgrid";

    private final DlqService dlqService;

    @Value("${app.providers.email.api-key}")
    private String apiKey;

    @Value("${app.providers.email.from}")
    private String fromAddress;

    @Value("${app.providers.email.simulated-failure-rate:0.0}")
    private double simulatedFailureRate;

    @Value("${app.providers.email.simulated-latency-ms:0}")
    private long simulatedLatencyMs;

    @CircuitBreaker(name = "emailProvider", fallbackMethod = "emailFallback")
    @Retry(name = "emailProvider")
    public void sendEmail(Notification notification) {
        log.info("SendGrid call: from={} to={} notificationId={}",
                fromAddress, notification.getRecipientId(), notification.getId());

        simulateLatency();

        if (ThreadLocalRandom.current().nextDouble() < simulatedFailureRate) {
            throw new IllegalStateException("Simulated SendGrid 5xx error");
        }

        log.info("SendGrid delivered email: notificationId={} apiKeyPrefix={}",
                notification.getId(), apiKeyPrefix());
    }

    @SuppressWarnings("unused")
    private void emailFallback(Notification notification, CallNotPermittedException ex) {
        log.warn("Email circuit OPEN — publishing to DLQ for notificationId={}", notification.getId());
        dlqService.publishToDlq(notification, "Circuit open: " + ex.getMessage(), 0);
        throw new DeliveryFailedException("Email circuit open", ex);
    }

    @SuppressWarnings("unused")
    private void emailFallback(Notification notification, Throwable ex) {
        log.error("Email delivery failed after retries — publishing to DLQ notificationId={}",
                notification.getId(), ex);
        dlqService.publishToDlq(notification, "Retries exhausted: " + ex.getMessage(), 0);
        throw new DeliveryFailedException("Email retries exhausted", ex);
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

    private String apiKeyPrefix() {
        if (apiKey == null || apiKey.length() < 4) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****";
    }
}
