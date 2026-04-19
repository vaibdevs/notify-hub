package com.notifyhub.dlq;

import com.notifyhub.notification.NotificationChannel;
import com.notifyhub.notification.NotificationPriority;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class DlqMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private UUID notificationId;
    private String tenantId;
    private String recipientId;
    private NotificationChannel channel;
    private NotificationPriority priority;
    private String templateId;
    private String content;
    private String reason;
    private Integer attemptCount;
    private LocalDateTime failedAt;
}
