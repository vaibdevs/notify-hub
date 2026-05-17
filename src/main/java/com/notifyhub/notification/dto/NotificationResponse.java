package com.notifyhub.notification.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.notifyhub.notification.NotificationChannel;
import com.notifyhub.notification.NotificationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotificationResponse {

    private UUID notificationId;
    private NotificationStatus status;
    private NotificationChannel channel;
    private Integer attempts;
    private LocalDateTime createdAt;
    private LocalDateTime deliveredAt;
}
