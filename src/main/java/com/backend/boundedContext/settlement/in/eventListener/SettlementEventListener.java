package com.backend.boundedContext.settlement.in.eventListener;

import com.backend.boundedContext.settlement.app.SettlementFacade;
import com.backend.shared.order.event.OrderConfirmedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 컨텍스트의 구매 확정 이벤트를 받아 정산 후보를 만든다.
 *
 * Outbox 릴레이가 전달한다(개발 가이드 6.5, TRSS-121 4-1단계).
 * 릴레이는 이벤트 하나마다 트랜잭션을 열고 그 안에서 이 리스너를 부른 뒤 outbox 행을 PUBLISHED로 바꾼다.
 * 이 리스너는 @Transactional(REQUIRED)로 그 트랜잭션에 참여하므로
 *   후보 생성이 성공하면: 후보 저장과 PUBLISHED 표시가 함께 커밋된다
 *   후보 생성이 실패하면(DB 오류 등): 둘 다 롤백되고 outbox는 PENDING으로 남아 릴레이가 다시 전달한다
 *     (최대 custom.outbox.maxRetryCount번, 그래도 실패하면 FAILED + ERROR 로그 → Slack 알림)
 *
 * 예전처럼 AFTER_COMMIT + REQUIRES_NEW로 두면 실패해도 PUBLISHED가 커밋돼 이벤트가 다시 오지 않는다(유실).
 * 같은 이벤트가 두 번 와도 주문 항목(order_item_id)으로 이미 있는 후보는 건너뛰므로 결과가 같다(멱등).
 */
@Component
@RequiredArgsConstructor
public class SettlementEventListener {

    private final SettlementFacade settlementFacade;

    @EventListener
    @Transactional
    public void handle(OrderConfirmedEvent event) {
        settlementFacade.createCandidates(event.orderId(), event.confirmedAt(), event.items());
    }
}