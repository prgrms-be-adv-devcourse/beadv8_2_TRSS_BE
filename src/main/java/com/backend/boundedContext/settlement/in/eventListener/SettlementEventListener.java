package com.backend.boundedContext.settlement.in.eventListener;

import com.backend.boundedContext.settlement.app.SettlementFacade;
import com.backend.shared.order.event.OrderConfirmedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

// 주문 컨텍스트의 구매 확정 이벤트를 받아 정산 후보 생성 시작
@Component
@RequiredArgsConstructor
public class SettlementEventListener {

    private final SettlementFacade settlementFacade;

    /**
     * 구매 확정 트랜잭션이 커밋된 뒤에만 실행하고(AFTER_COMMIT), 커밋이 끝난 시점이라 새 트랜잭션을 연다(REQUIRES_NEW).
     * Outbox 도입 전 1단계 방식이며, 2단계에서는 @EventListener + @Transactional로만 바꾼다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handle(OrderConfirmedEvent event) {
        settlementFacade.createCandidates(event.orderId(), event.confirmedAt(), event.items());
    }
}
