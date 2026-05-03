package com.notifyhub.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The circuit breaker and retry registries are auto-configured by resilience4j-spring-boot3
 * from the resilience4j.* properties in application.yml. This class eagerly instantiates
 * the named instances so state transitions start firing before the first real call.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ResilienceConfig {

    private static final String[] PROVIDERS = {"emailProvider", "smsProvider", "pushProvider"};

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;

    @EventListener(ApplicationReadyEvent.class)
    public void warmRegistries() {
        for (String provider : PROVIDERS) {
            var cb = circuitBreakerRegistry.circuitBreaker(provider);
            var retry = retryRegistry.retry(provider);
            cb.getEventPublisher().onStateTransition(event ->
                    log.info("CircuitBreaker [{}] state transition: {} -> {}",
                            provider,
                            event.getStateTransition().getFromState(),
                            event.getStateTransition().getToState()));
            retry.getEventPublisher().onRetry(event ->
                    log.warn("Retry [{}] attempt {} failed: {}",
                            provider, event.getNumberOfRetryAttempts(),
                            event.getLastThrowable() == null ? "n/a" : event.getLastThrowable().getMessage()));
        }
    }
}
