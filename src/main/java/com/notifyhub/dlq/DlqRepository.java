package com.notifyhub.dlq;

import com.notifyhub.notification.NotificationChannel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory registry of DLQ messages keyed by notification id. The authoritative record
 * for failed attempts is the delivery_logs table; this store keeps the raw DLQ payloads
 * available for the admin replay endpoint without introducing another table.
 */
@Component
public class DlqRepository {

    private final Map<UUID, DlqMessage> messages = new ConcurrentHashMap<>();
    private final Map<NotificationChannel, AtomicLong> channelCounts = new ConcurrentHashMap<>();

    public DlqRepository() {
        for (NotificationChannel channel : NotificationChannel.values()) {
            channelCounts.put(channel, new AtomicLong(0));
        }
    }

    public void save(DlqMessage message) {
        messages.put(message.getNotificationId(), message);
        channelCounts.get(message.getChannel()).incrementAndGet();
    }

    public Optional<DlqMessage> findById(UUID notificationId) {
        return Optional.ofNullable(messages.get(notificationId));
    }

    public void remove(UUID notificationId) {
        DlqMessage removed = messages.remove(notificationId);
        if (removed != null) {
            channelCounts.get(removed.getChannel()).decrementAndGet();
        }
    }

    public List<DlqMessage> findAll() {
        return Collections.unmodifiableList(new ArrayList<>(messages.values()));
    }

    public long size() {
        return messages.size();
    }

    public long sizeByChannel(NotificationChannel channel) {
        return channelCounts.getOrDefault(channel, new AtomicLong(0)).get();
    }
}
