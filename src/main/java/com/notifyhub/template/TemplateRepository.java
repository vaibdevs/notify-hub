package com.notifyhub.template;

import com.notifyhub.notification.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TemplateRepository extends JpaRepository<Template, UUID> {

    Optional<Template> findByTemplateIdAndChannel(String templateId, NotificationChannel channel);

    Optional<Template> findByTemplateId(String templateId);
}
