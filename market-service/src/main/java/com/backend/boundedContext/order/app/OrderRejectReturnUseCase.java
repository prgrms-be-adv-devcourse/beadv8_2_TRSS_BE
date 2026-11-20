package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.OrderPolicy;
import com.backend.boundedContext.order.in.dto.ReturnResultResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.order.dto.ConfirmType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 관리자 반품 거절
 * 주문을 PAID로 되돌린다. 자동 확정 기준일(배송 완료 + 7일, 배송지 없으면 결제 + 7일)이 이미 지났으면
 * 같은 트랜잭션에서 구매 확정(RETURN_REJECTED)하고 OrderConfirmedEvent를 발행한다(정산 후보).
 * 기준일 전이면 PAID로 두고 자동 확정 스케줄러에 맡긴다(거절로 기간이 늘어나지 않는다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderRejectReturnUseCase {

    private final OrderRepository orderRepository;
    private final OrderPolicy orderPolicy;
    private final OrderConfirmSupport orderConfirmSupport;

    public ReturnResultResponse reject(Long orderId, String reason) {
        // 다른 관리자의 승인·거절과 겹칠 수 있어 주문 행을 잠근 뒤 상태를 다시 본다
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new DomainException(OrderErrorCode.ORDER_NOT_FOUND));

        LocalDateTime now = LocalDateTime.now();
        order.rejectReturn(reason, now);

        boolean immediateConfirm = order.isAutoConfirmable(now, orderPolicy.getAutoConfirmDays());
        if (immediateConfirm) {
            orderConfirmSupport.confirm(order, ConfirmType.RETURN_REJECTED, now);
        }

        // 거절 사유(자유 입력)는 남기지 않는다
        log.info("반품 거절: orderId={}, immediateConfirm={}", order.getId(), immediateConfirm);
        return new ReturnResultResponse(order.getId(), order.getStatus(), order.getConfirmType());
    }
}
