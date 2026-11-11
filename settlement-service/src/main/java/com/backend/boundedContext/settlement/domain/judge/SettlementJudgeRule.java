package com.backend.boundedContext.settlement.domain.judge;

import com.backend.boundedContext.settlement.domain.SettlementJudgement;

/**
 * 판별 규칙 하나 = 질문 하나 (책임 연쇄)
 *
 * 새 규칙은 이 인터페이스의 구현 클래스(@Component)를 하나 추가하면 SettlementJudgeChain이 자동으로 모은다.
 * 기존 규칙과 체인 코드는 고치지 않는다.
 *
 * 규칙은 "시간이 지나면 답이 바뀔 수 있는 조건"만 본다. 바뀌지 않는 조건(필수값·금액·중복·구매 확정)은
 * 구매 확정 이벤트를 받을 때 한 번 본다(SettlementCreateCandidatesUseCase).
 */
public interface SettlementJudgeRule {

    /** 실행 순서. 작을수록 먼저. BLOCK이 HOLD보다 먼저 나와야 하므로 데이터 규칙을 앞에 둔다 */
    int order();

    /** PASS면 다음 규칙으로, HOLD·BLOCK이면 체인이 여기서 멈춘다 */
    SettlementJudgement judge(SettlementJudgeContext context);
}
