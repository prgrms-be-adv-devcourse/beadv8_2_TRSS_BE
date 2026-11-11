package com.backend.boundedContext.notification.app;

import com.backend.boundedContext.notification.domain.Notification;
import com.backend.boundedContext.notification.out.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 알림 저장. 이벤트 타입을 모르고 알림에 필요한 값만 받음. 이벤트를 이 값으로 바꾸는 일은 이벤트 리스너(in)가 함
@Service
@RequiredArgsConstructor
public class NotificationCreateUseCase {

    private final NotificationRepository notificationRepository;

    // 주문 완료(결제 성공) 알림
    public void createOrderPaid(Long memberId, Long orderId) {
        notificationRepository.save(Notification.orderPaid(memberId, orderId));
    }

    // 배송 시작(송장 등록) 알림. 택배사 표시 이름·송장번호를 문구에 넣고 따로도 저장함
    public void createDeliveryStarted(Long memberId, Long orderId, String carrierName, String trackingNumber) {
        notificationRepository.save(Notification.deliveryStarted(memberId, orderId, carrierName, trackingNumber));
    }

    // 배송 완료 알림
    public void createDeliveryCompleted(Long memberId, Long orderId) {
        notificationRepository.save(Notification.deliveryCompleted(memberId, orderId));
    }
}
