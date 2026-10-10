package com.backend.boundedContext.settlement.in;

import com.backend.shared.settlement.out.SettlementApi;
import org.springframework.stereotype.Component;

/**
 * {@link SettlementApi}의 구현체
 *
 * TODO 임시 구현(Stub), 실제 구현 시 SettlementFacade 호출로 바꾼다.
 * 회원 컨텍스트가 먼저 개발할 수 있도록 미정산 금액은 항상 0을 돌려주고,
 * 제재(holdSeller)는 아무것도 하지 않는다.
 */
@Component
public class SettlementApiAdapter implements SettlementApi {

    /** 임시 구현: 미정산 금액이 없다고 본다(판매자 철회·회원 탈퇴가 막히지 않음) */
    @Override
    public long getUnsettledAmount(Long sellerId) {
        return 0L;
    }

    /** 임시 구현: 아무것도 하지 않는다 */
    @Override
    public void holdSeller(Long sellerId, String reason) {
    }
}