package com.backend.boundedContext.settlement.in;

import com.backend.boundedContext.settlement.app.SettlementFacade;
import com.backend.boundedContext.settlement.out.SettlementMemberPort;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.order.dto.ConfirmedOrderItemDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/**
 * 개발용 정산 후보 시드(@Order(5))
 * 후보가 하나라도 있으면 아무것도 하지 않는다(멱등)
 *
 * settlement-service는 주문 서비스가 아니라서 OrderConfirmedEvent를 직접 발행하지 않는다
 * (정산이 order.events 토픽에 가짜 주문 이벤트를 올리면 다른 컨슈머가 실제 주문으로 오해할 수 있다).
 * 대신 컨슈머(SettlementEventListener)가 이벤트를 받았을 때와 같은 메서드(SettlementFacade.createCandidates)를 직접 불러
 * 지난달 확정분 3건을 만든다. 후보가 만들어지는 결과는 이벤트 경로와 같다.
 *
 * 결제 시드(PaymentDataInit)가 같은 주문 -1(25,000원)·-2(9,999원)로 HELD 홀딩을 만들어 두지만, 결제는 아직 다른 앱이라
 * 결제 서비스 연결 전에는 월 정산을 실행해도 지급 단계에서 실패한다(PaymentApiClient.transfer 예외).
 * 시드 판매자(회원 2번)의 sellerId는 SettlementMemberPort로 찾는다. 회원 서비스 연결 전에는 임시 구현이 회원 ID와 같은 값을 돌려준다.
 *
 * TODO 주문 서비스가 실제로 구매 확정 이벤트를 발행하게 되면 PaymentDataInit의 홀딩 시드와 함께 지우기
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

    private final SettlementFacade settlementFacade;
    private final SettlementMemberPort settlementMemberPort;

    @Override
    public void run(ApplicationArguments args) {
        if (settlementFacade.hasAnyCandidate()) return;

        Long sellerId = settlementMemberPort.findSellerByMemberId(SEED_SELLER_MEMBER_ID)
                .map(SellerDto::sellerId)
                .orElse(null);
        if (sellerId == null) {
            log.info("시드 판매자(회원 {}번)가 없어 정산 후보 시드를 건너뜀", SEED_SELLER_MEMBER_ID);
            return;
        }

        // createCandidates는 Facade에서 트랜잭션을 연다. 주문 하나 = 구매 확정 이벤트 하나와 같은 단위로 부른다
        LocalDateTime confirmedAt = YearMonth.now().minusMonths(1).atDay(15).atTime(12, 0);
        int created = settlementFacade.createCandidates(-1L, confirmedAt, List.of(
                new ConfirmedOrderItemDto(-1L, -1L, sellerId, 10_000L, 1, 10_000L),
                new ConfirmedOrderItemDto(-2L, -2L, sellerId, 15_000L, 1, 15_000L)));
        created += settlementFacade.createCandidates(-2L, confirmedAt.plusDays(1), List.of(
                new ConfirmedOrderItemDto(-3L, -3L, sellerId, 9_999L, 1, 9_999L)));
        log.info("정산 후보 시드 생성: sellerId={} 지난달 확정 {}항목", sellerId, created);
    }
}
