package com.backend.boundedContext.notification.app;

import com.backend.boundedContext.notification.domain.Notification;
import com.backend.boundedContext.notification.out.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 알림 저장
 *
 * 이벤트 타입을 모르고 알림에 필요한 값만 받음. 이벤트를 이 값으로 바꾸는 일은 이벤트 리스너(in)가 함.
 */
@Service
@RequiredArgsConstructor
public class NotificationCreateUseCase {
    private final NotificationRepository notificationRepository;

    // 주문 완료(결제 성공) 알림
    public void createOrderPaid(Long memberId, Long orderId) {
        notificationRepository.save(Notification.orderPaid(memberId, orderId));
    }
}
