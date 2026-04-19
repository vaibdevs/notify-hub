package com.notifyhub.metrics;

import com.notifyhub.dlq.DlqRepository;
import com.notifyhub.notification.NotificationChannel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class NotificationMetrics {

    private final MeterRegistry meterRegistry;
    private final DlqRepository dlqRepository;

    private final Map<NotificationChannel, Counter> sentCounters = new EnumMap<>(NotificationChannel.class);
    private final Map<NotificationChannel, Counter> deliveredCounters = new EnumMap<>(NotificationChannel.class);
    private final Map<NotificationChannel, Counter> failedCounters = new EnumMap<>(NotificationChannel.class);
    private final Map<NotificationChannel, Timer> deliveryTimers = new EnumMap<>(NotificationChannel.class);

    @PostConstruct
    public void register() {
        for (NotificationChannel channel : NotificationChannel.values()) {
            String tag = channel.name().toLowerCase();
            sentCounters.put(channel, Counter.builder("notifications_sent_total")
                    .description("Notifications accepted by NotifyHub")
                    .tag("channel", tag)
                    .register(meterRegistry));
            deliveredCounters.put(channel, Counter.builder("notifications_delivered_total")
                    .description("Notifications successfully delivered")
                    .tag("channel", tag)
                    .register(meterRegistry));
            failedCounters.put(channel, Counter.builder("notifications_failed_total")
                    .description("Notifications that failed all delivery attempts")
                    .tag("channel", tag)
                    .register(meterRegistry));
            deliveryTimers.put(channel, Timer.builder("delivery_latency")
                    .description("Latency from send to successful delivery")
                    .tag("channel", tag)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(meterRegistry));

            Gauge.builder("dlq_size", dlqRepository, r -> r.sizeByChannel(channel))
                    .description("Number of messages currently in the DLQ")
                    .tag("channel", tag)
                    .register(meterRegistry);
        }
    }

    public void incrementSent(NotificationChannel channel) {
        sentCounters.get(channel).increment();
    }

    public void incrementDelivered(NotificationChannel channel) {
        deliveredCounters.get(channel).increment();
    }

    public void incrementFailed(NotificationChannel channel) {
        failedCounters.get(channel).increment();
    }

    public void recordDeliveryLatency(NotificationChannel channel, long nanos) {
        deliveryTimers.get(channel).record(nanos, TimeUnit.NANOSECONDS);
    }

    public double deliveryRate(NotificationChannel channel) {
        double sent = sentCounters.get(channel).count();
        if (sent == 0.0) {
            return 0.0;
        }
        return deliveredCounters.get(channel).count() / sent;
    }

    public double totalSent() {
        return sentCounters.values().stream().mapToDouble(Counter::count).sum();
    }

    public double totalDelivered() {
        return deliveredCounters.values().stream().mapToDouble(Counter::count).sum();
    }

    public double totalFailed() {
        return failedCounters.values().stream().mapToDouble(Counter::count).sum();
    }

    public double overallP99LatencyMs() {
        return deliveryTimers.values().stream()
                .mapToDouble(t -> t.percentile(0.99, TimeUnit.MILLISECONDS))
                .max()
                .orElse(0.0);
    }
}
