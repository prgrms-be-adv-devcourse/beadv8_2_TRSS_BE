package com.backend.boundedContext.settlement.domain.judge;

import com.backend.boundedContext.settlement.domain.SettlementJudgement;
import com.backend.shared.member.dto.SellerStatus;
import org.springframework.stereotype.Component;

/**
 * 판매자 상태: 관리자 제재(SANCTIONED)면 보류. 그 밖의 상태(철회 완료 등)는 지금처럼 그대로 진행한다
 * 판매자 행이 없으면(null) 상태만 모르는 것이므로 PASS하고, 지급 단계의 SELLER_NOT_FOUND(재시도 불가)에 맡긴다
 * 지급 직전에도 SettlementPayoutUseCase가 제재 여부를 한 번 더 본다(판별 이후 제재된 경우)
 */
@Component
public class SellerStatusRule implements SettlementJudgeRule {

    public static final String SANCTIONED_REASON = "관리자 제재된 판매자라 정산을 보류합니다.";

    @Override
    public int order() {
        return 30;
    }

    @Override
    public SettlementJudgement judge(SettlementJudgeContext context) {
        if (context.sellerStatus() == SellerStatus.SANCTIONED) return SettlementJudgement.hold(SANCTIONED_REASON);
        return SettlementJudgement.pass();
    }
}
