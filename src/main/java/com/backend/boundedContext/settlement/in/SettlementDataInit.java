package com.backend.boundedContext.settlement.in;

import com.backend.boundedContext.settlement.app.SettlementFacade;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.dto.ConfirmedOrderItemDto;
import com.backend.shared.order.event.OrderConfirmedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/**
 * 개발용 정산 후보 시드(@Order(5))
 * 후보가 하나라도 있으면 아무것도 하지 않는다(멱등)
 * 주문 흐름이 아직 없으므로 더미 OrderConfirmedEvent를 직접 발행해,
 * 실제와 같은 이벤트 → SettlementEventListener → 후보 생성 경로로 지난달 확정분 3건을 만든다.
 * 주문·주문 항목 ID는 임시로 발행한 ID를 쓴다.
 * TODO 주문 컨텍스트가 실제로 이벤트를 발행하게 되면 이 시드를 지운다
 */
@Slf4j
@Component
@Order(5)
@RequiredArgsConstructor
public class SettlementDataInit implements ApplicationRunner {

    private static final Long SEED_SELLER_ID = 2L;
    /** 더미 구매자 회원 ID. 정산은 구매자를 쓰지 않으므로 값만 채운다 */
    private static final Long SEED_BUYER_ID = 3L;

    /** 같은 클래스 안 호출은 @Transactional이 걸리지 않으므로 프록시인 자기 자신을 거친다 */
    @Lazy
    private final SettlementDataInit self;
    private final SettlementFacade settlementFacade;
    private final EventPublisher eventPublisher;

    @Override
    public void run(ApplicationArguments args) {
        if (settlementFacade.hasAnyCandidate()) return;

        LocalDateTime confirmedAt = YearMonth.now().minusMonths(1).atDay(15).atTime(12, 0);
        self.publishOrderConfirmed(-1L, confirmedAt, List.of(
                new ConfirmedOrderItemDto(-1L, -1L, SEED_SELLER_ID, 10_000L, 1, 10_000L),
                new ConfirmedOrderItemDto(-2L, -2L, SEED_SELLER_ID, 15_000L, 1, 15_000L)));
        self.publishOrderConfirmed(-2L, confirmedAt.plusDays(1), List.of(
                new ConfirmedOrderItemDto(-3L, -3L, SEED_SELLER_ID, 9_999L, 1, 9_999L)));
        log.info("더미 구매 확정 이벤트 2건 발행: sellerId={} 지난달 확정 3항목", SEED_SELLER_ID);
    }

    /**
     * 리스너가 AFTER_COMMIT이라 트랜잭션 안에서 발행해야 커밋 직후 실행된다.
     * 트랜잭션 없이 발행하면 리스너가 불리지 않고 이벤트가 그냥 버려진다.
     */
    @Transactional
    public void publishOrderConfirmed(Long orderId, LocalDateTime confirmedAt, List<ConfirmedOrderItemDto> items) {
        eventPublisher.publish(new OrderConfirmedEvent(orderId, SEED_BUYER_ID, confirmedAt, ConfirmType.MANUAL, items));
    }
}