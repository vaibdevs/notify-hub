package com.notifyhub.sms;

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
public class SmsService {

    public static final String PROVIDER = "twilio";

    private final DlqService dlqService;

    @Value("${app.providers.sms.account-sid}")
    private String accountSid;

    @Value("${app.providers.sms.auth-token}")
    private String authToken;

    @Value("${app.providers.sms.from}")
    private String fromNumber;

    @Value("${app.providers.sms.simulated-failure-rate:0.0}")
    private double simulatedFailureRate;

    @Value("${app.providers.sms.simulated-latency-ms:0}")
    private long simulatedLatencyMs;

    @CircuitBreaker(name = "smsProvider", fallbackMethod = "smsFallback")
    @Retry(name = "smsProvider")
    public void sendSms(Notification notification) {
        log.info("Twilio call: sid={} from={} to={} notificationId={}",
                sidPrefix(), fromNumber, notification.getRecipientId(), notification.getId());

        simulateLatency();

        if (ThreadLocalRandom.current().nextDouble() < simulatedFailureRate) {
            throw new IllegalStateException("Simulated Twilio 5xx error");
        }

        log.info("Twilio delivered SMS: notificationId={} authTokenPrefix={}",
                notification.getId(), tokenPrefix());
    }

    @SuppressWarnings("unused")
    private void smsFallback(Notification notification, CallNotPermittedException ex) {
        log.warn("SMS circuit OPEN — publishing to DLQ for notificationId={}", notification.getId());
        dlqService.publishToDlq(notification, "Circuit open: " + ex.getMessage(), 0);
        throw new DeliveryFailedException("SMS circuit open", ex);
    }

    @SuppressWarnings("unused")
    private void smsFallback(Notification notification, Throwable ex) {
        log.error("SMS delivery failed after retries — publishing to DLQ notificationId={}",
                notification.getId(), ex);
        dlqService.publishToDlq(notification, "Retries exhausted: " + ex.getMessage(), 0);
        throw new DeliveryFailedException("SMS retries exhausted", ex);
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

    private String sidPrefix() {
        return accountSid == null || accountSid.length() < 4
                ? "****" : accountSid.substring(0, 4) + "****";
    }

    private String tokenPrefix() {
        return authToken == null || authToken.length() < 4
                ? "****" : authToken.substring(0, 4) + "****";
    }
}
