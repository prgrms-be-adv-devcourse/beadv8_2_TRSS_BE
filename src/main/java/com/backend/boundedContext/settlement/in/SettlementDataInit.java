package com.backend.boundedContext.settlement.in;

import com.backend.boundedContext.settlement.app.SettlementFacade;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.ConfirmType;
import com.backend.shared.order.dto.ConfirmedOrderItemDto;
import com.backend.shared.order.event.OrderConfirmedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
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
 *
 * 결제 시드(PaymentDataInit, @Order(3))가 같은 주문 -1(25,000원)·-2(9,999원)로 HELD 홀딩을 만들어 두므로,
 * 월 정산을 실행하면 지급까지 PAID로 끝난다. 주문 ID나 금액을 바꾸면 PaymentDataInit도 함께 바꿔야 한다.
 * 시드 판매자(회원 2번)의 sellerId는 자동 증가라 회원 ID와 다르므로 MemberApi로 찾는다.
 * 시드 판매자가 없으면(MEMBER_SEED_PASSWORD가 없는 환경 등) 아무것도 하지 않는다.
 *
 * TODO 주문 컨텍스트가 실제로 이벤트를 발행하게 되면 PaymentDataInit의 홀딩 시드와 함께 지우기
 */
@Slf4j
@Component
@Profile("local")
@Order(5)
@RequiredArgsConstructor
public class SettlementDataInit implements ApplicationRunner {

    /** 시드 판매자 회원(seller@palette.test)
     * MemberDataInit이 APPROVED 판매자로 만든다 */
    private static final Long SEED_SELLER_MEMBER_ID = 2L;
    /** 더미 구매자 회원 ID
     * 결제 시드의 홀딩 구매자와 같다(정산은 구매자를 쓰지 않으므로 값만 채운다) */
    private static final Long SEED_BUYER_ID = 3L;

    /** 같은 클래스 안 호출은 @Transactional이 걸리지 않으므로 프록시인 자기 자신을 거친다 */
    @Lazy
    private final SettlementDataInit self;
    private final SettlementFacade settlementFacade;
    private final EventPublisher eventPublisher;
    private final MemberApi memberApi;

    @Override
    public void run(ApplicationArguments args) {
        if (settlementFacade.hasAnyCandidate()) return;

        Long sellerId = memberApi.findSellerByMemberId(SEED_SELLER_MEMBER_ID)
                .map(SellerDto::sellerId)
                .orElse(null);
        if (sellerId == null) {
            log.info("시드 판매자(회원 {}번)가 없어 정산 후보 시드를 건너뜀", SEED_SELLER_MEMBER_ID);
            return;
        }

        LocalDateTime confirmedAt = YearMonth.now().minusMonths(1).atDay(15).atTime(12, 0);
        self.publishOrderConfirmed(-1L, confirmedAt, List.of(
                new ConfirmedOrderItemDto(-1L, -1L, sellerId, 10_000L, 1, 10_000L),
                new ConfirmedOrderItemDto(-2L, -2L, sellerId, 15_000L, 1, 15_000L)));
        self.publishOrderConfirmed(-2L, confirmedAt.plusDays(1), List.of(
                new ConfirmedOrderItemDto(-3L, -3L, sellerId, 9_999L, 1, 9_999L)));
        log.info("더미 구매 확정 이벤트 2건 발행: sellerId={} 지난달 확정 3항목", sellerId);
    }

    /**
     * 리스너가 AFTER_COMMIT이라 트랜잭션 안에서 발행해야 커밋 직후 실행
     * 트랜잭션 없이 발행하면 리스너가 불리지 않고 이벤트가 그냥 버려진다
     */
    @Transactional
    public void publishOrderConfirmed(Long orderId, LocalDateTime confirmedAt, List<ConfirmedOrderItemDto> items) {
        eventPublisher.publish(new OrderConfirmedEvent(orderId, SEED_BUYER_ID, confirmedAt, ConfirmType.MANUAL, items));
    }
}