package com.backend.boundedContext.notification.app;

import com.backend.boundedContext.notification.domain.Notification;
import com.backend.boundedContext.notification.domain.NotificationType;
import com.backend.boundedContext.notification.out.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 알림 저장. 이벤트 타입을 모르고 알림에 필요한 값만 받음. 이벤트를 이 값으로 바꾸는 일은 이벤트 리스너(in)가 함
@Service
@RequiredArgsConstructor
public class NotificationCreateUseCase {

    private final NotificationRepository notificationRepository;

    // 주문 완료(결제 성공) 알림. 대표 상품명·상품 개수를 같이 저장함
    public void createOrderPaid(Long memberId, Long orderId, String productName, Integer itemCount) {
        notificationRepository.save(Notification.orderPaid(memberId, orderId, productName, itemCount));
    }

    // 배송 시작(송장 등록) 알림. 택배사 표시 이름·송장번호를 문구에 넣고 따로도 저장함
    public void createDeliveryStarted(Long memberId, Long orderId, String carrierName, String trackingNumber) {
        notificationRepository.save(Notification.deliveryStarted(memberId, orderId, carrierName, trackingNumber, findOrderPaid(memberId, orderId)));
    }

    // 배송 완료 알림
    public void createDeliveryCompleted(Long memberId, Long orderId) {
        notificationRepository.save(Notification.deliveryCompleted(memberId, orderId, findOrderPaid(memberId, orderId)));
    }

    // 배송 알림이 상품명·개수를 가져올 같은 주문의 결제 완료 알림. 없으면 null
    private Notification findOrderPaid(Long memberId, Long orderId) {
        return notificationRepository.findFirstByMemberIdAndOrderIdAndType(memberId, orderId, NotificationType.ORDER_PAID).orElse(null);
    }
}
