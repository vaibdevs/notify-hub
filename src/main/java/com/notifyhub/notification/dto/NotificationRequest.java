package com.notifyhub.notification.dto;

import com.notifyhub.notification.NotificationChannel;
import com.notifyhub.notification.NotificationPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationRequest {

    @NotBlank(message = "tenantId is required")
    @Size(max = 100)
    private String tenantId;

    @NotBlank(message = "recipientId is required")
    @Size(max = 255)
    private String recipientId;

    @NotNull(message = "channel is required")
    private NotificationChannel channel;

    private NotificationPriority priority;

    @NotBlank(message = "templateId is required")
    @Size(max = 100)
    private String templateId;

    private Map<String, Object> templateData;

    private String platform;
}
