package com.notifyhub.notification;

import com.notifyhub.deliverylog.DeliveryLog;
import com.notifyhub.deliverylog.DeliveryLogRepository;
import com.notifyhub.notification.dto.NotificationRequest;
import com.notifyhub.notification.dto.NotificationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final DeliveryLogRepository deliveryLogRepository;

    @PostMapping("/notify")
    public ResponseEntity<NotificationResponse> send(@Valid @RequestBody NotificationRequest request) {
        NotificationResponse response = notificationService.submit(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/notifications/{id}")
    public ResponseEntity<NotificationResponse> getStatus(@PathVariable UUID id) {
        return ResponseEntity.ok(notificationService.getStatus(id));
    }

    @GetMapping("/notifications/{id}/attempts")
    public ResponseEntity<List<Map<String, Object>>> getAttempts(@PathVariable UUID id) {
        notificationService.getNotification(id);
        List<Map<String, Object>> attempts = deliveryLogRepository
                .findByNotificationIdOrderByAttemptNumberAsc(id)
                .stream()
                .map(this::toDto)
                .toList();
        return ResponseEntity.ok(attempts);
    }

    private Map<String, Object> toDto(DeliveryLog log) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("attemptNumber", log.getAttemptNumber());
        map.put("status", log.getStatus());
        map.put("provider", log.getProvider());
        map.put("attemptedAt", log.getAttemptedAt());
        if (log.getErrorMessage() != null) {
            map.put("errorMessage", log.getErrorMessage());
        }
        return map;
    }
}
