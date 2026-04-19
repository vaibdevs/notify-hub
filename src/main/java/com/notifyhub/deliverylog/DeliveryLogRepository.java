package com.notifyhub.deliverylog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DeliveryLogRepository extends JpaRepository<DeliveryLog, UUID> {

    List<DeliveryLog> findByNotificationIdOrderByAttemptNumberAsc(UUID notificationId);

    long countByStatus(DeliveryLogStatus status);

    @Query("SELECT COALESCE(MAX(d.attemptNumber), 0) FROM DeliveryLog d WHERE d.notificationId = :notificationId")
    Integer findMaxAttemptNumber(@Param("notificationId") UUID notificationId);

    List<DeliveryLog> findByStatusInOrderByAttemptedAtDesc(List<DeliveryLogStatus> statuses);
}
