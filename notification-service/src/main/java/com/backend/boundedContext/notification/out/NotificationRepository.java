package com.backend.boundedContext.notification.out;

import com.backend.boundedContext.notification.domain.Notification;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
}
