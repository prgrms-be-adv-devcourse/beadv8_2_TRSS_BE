package com.backend.shared.order.event;

import com.backend.shared.event.DomainEvent;
import com.backend.shared.order.dto.ReturnReasonCode;

import java.time.LocalDateTime;

// 반품 승인. 관리자가 승인한 트랜잭션에서 발행한다(재고 복원과 같은 트랜잭션). 결제 컨텍스트가 받아 환불한다
// refundAmount: 주문의 pay_amount(배송비 포함 결제 금액 전액). 쿠폰 할인분은 돌려주지 않는다.
//   payment는 금액 내역(배송비·장바구니 쿠폰 지원금)을 자기 결제 건에 저장된 값으로 나누고, 이 값은 대조용으로만 쓴다
// returnReasonCode: 반품 사유. 결제는 단순 변심(CHANGE_OF_MIND)이면 처음 배송비를 돌려주지 않고 반품 배송비를 빼고,
//   그 밖의 사유나 null(사유를 싣기 전에 발행된 이벤트)이면 전액 환불한다
public record OrderReturnedEvent(Long orderId, Long buyerId, long refundAmount,
                                 LocalDateTime returnedAt, ReturnReasonCode returnReasonCode) implements DomainEvent {

    // 사유를 싣기 전의 발행 코드(market)가 그대로 컴파일되게 남겨 둔다. 사유는 null
    public OrderReturnedEvent(Long orderId, Long buyerId, long refundAmount, LocalDateTime returnedAt) {
        this(orderId, buyerId, refundAmount, returnedAt, null);
    }

    @Override
    public String aggregateType() {
        return "ORDER";
    }

    @Override
    public Long aggregateId() {
        return orderId;
    }
}
