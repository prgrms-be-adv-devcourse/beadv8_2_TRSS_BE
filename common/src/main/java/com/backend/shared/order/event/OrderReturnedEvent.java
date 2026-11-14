package com.backend.shared.order.event;

import com.backend.shared.event.DomainEvent;

import java.time.LocalDateTime;

// 반품 승인. 관리자가 승인한 트랜잭션에서 발행한다(재고 복원과 같은 트랜잭션). 결제 컨텍스트가 받아 환불한다
// refundAmount: 주문의 pay_amount(배송비 포함 결제 금액 전액). 쿠폰 할인분은 돌려주지 않는다.
//   payment는 금액 내역(배송비·장바구니 쿠폰 지원금)을 자기 결제 건에 저장된 값으로 나누고, 이 값은 대조용으로만 쓴다
public record OrderReturnedEvent(Long orderId, Long buyerId, long refundAmount,
                                 LocalDateTime returnedAt) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "ORDER";
    }

    @Override
    public Long aggregateId() {
        return orderId;
    }
}
