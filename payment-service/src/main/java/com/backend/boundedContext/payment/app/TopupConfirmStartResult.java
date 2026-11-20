package com.backend.boundedContext.payment.app;

import com.backend.boundedContext.payment.app.dto.TopupConfirmDto;
import com.backend.boundedContext.payment.domain.PgProvider;
import com.backend.boundedContext.payment.out.pg.PgPaymentRef;

/**
 * 충전 승인 시작(Tx1) 결과. 승인을 보낼 PG(충전 건에 기록된 PG)를 함께 넘김
 * - STARTED: PROCESSING으로 선점함 → PG 승인 호출로 진행
 * - AMOUNT_MISMATCH: FAILED_AMOUNT_MISMATCH로 기록하고 커밋함 → 호출한 쪽에서 400 AMOUNT_MISMATCH를 던져야 함
 * - REPLAYED: 이미 승인 중이거나 승인된 충전 건에 같은 요청이 다시 옴 → PG를 부르지 않고 replay를 그대로 응답
 */
public record TopupConfirmStartResult(
        Long topupId,
        PgProvider pgProvider,
        Outcome outcome,
        TopupConfirmDto replay,  // REPLAYED일 때만 있음
        PgPaymentRef payment     // STARTED일 때만 있음. 승인 결과를 모를 때 바로 PG에 조회하는 데 씀
) {
    public enum Outcome { STARTED, AMOUNT_MISMATCH, REPLAYED }

    public static TopupConfirmStartResult replayed(TopupConfirmDto replay, PgProvider pgProvider) {
        return new TopupConfirmStartResult(replay.topupId(), pgProvider, Outcome.REPLAYED, replay, null);
    }

    public boolean isStarted() {
        return outcome == Outcome.STARTED;
    }

    public boolean isReplayed() {
        return outcome == Outcome.REPLAYED;
    }
}
