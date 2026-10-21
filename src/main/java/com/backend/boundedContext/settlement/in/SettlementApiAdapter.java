package com.backend.boundedContext.settlement.in;

import com.backend.boundedContext.settlement.app.SettlementFacade;
import com.backend.shared.settlement.out.SettlementApi;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * {@link SettlementApi}의 구현체
 *
 * SettlementFacade 호출
 * 회원 ↔ 정산이 서로 호출하므로 Facade를 @Lazy로 주입해 시작 시 순환 참조를 끊는다(개발 가이드 6.2).
 * Facade의 @Transactional(REQUIRED)이 호출한 쪽(회원 컨텍스트) 트랜잭션에 참여한다.
 */
@Component
public class SettlementApiAdapter implements SettlementApi {

    private final SettlementFacade settlementFacade;

    public SettlementApiAdapter(@Lazy SettlementFacade settlementFacade) {
        this.settlementFacade = settlementFacade;
    }

    /** 미정산 금액 = READY 후보 + PAID가 아닌 정산서에 묶인 INCLUDED 후보의 지급액 합 (판매자 철회·회원 탈퇴 검사) */
    @Override
    public long getUnsettledAmount(Long sellerId) {
        return settlementFacade.getUnsettledAmount(sellerId);
    }

    /** 관리자 제재: 지급 전 정산서를 HOLD로 바꾼다.
     * 이후 집계되는 정산서는 판매자 상태(SANCTIONED)를 보고 HOLD로 만든다 */
    @Override
    public void holdSeller(Long sellerId, String reason) {
        settlementFacade.holdSeller(sellerId, reason);
    }
}