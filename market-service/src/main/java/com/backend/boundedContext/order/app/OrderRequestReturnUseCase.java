package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.ReturnReasonCode;
import com.backend.boundedContext.order.in.dto.ReturnRequestResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.out.MemberApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 구매자 반품 요청
 * 결제 완료(PAID)이고 배송 완료된 주문을 구매 확정 전까지 주문당 한 번 요청한다. 재고·결제·이벤트는 승인 때 처리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderRequestReturnUseCase {

    private final MemberApi memberApi;
    private final OrderRepository orderRepository;

    public ReturnRequestResponse request(Long buyerId, Long orderId, ReturnReasonCode reasonCode, String reason) {
        memberApi.checkActiveMember(buyerId);

        // 자동·수동 구매 확정과 겹칠 수 있어 주문 행을 잠가서 조회한다. 먼저 잠근 쪽만 반영된다
        Order order = orderRepository.findByIdAndBuyerIdForUpdate(orderId, buyerId)
                .orElseThrow(() -> new DomainException(OrderErrorCode.ORDER_NOT_FOUND));

        order.requestReturn(reasonCode, reason, LocalDateTime.now());

        // 상세 사유(자유 입력)·배송지는 남기지 않는다
        log.info("반품 요청: orderId={}, reasonCode={}", order.getId(), reasonCode);
        return new ReturnRequestResponse(order.getId(), order.getStatus(), order.getReturnRequestedAt());
    }
}
