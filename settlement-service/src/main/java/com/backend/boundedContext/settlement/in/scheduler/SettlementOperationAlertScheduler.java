package com.backend.boundedContext.settlement.in.scheduler;

import com.backend.boundedContext.settlement.app.SettlementFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 정산 운영 알림: 운영자가 알아야 하는 상태를 ERROR 로그로 남겨 기존 Slack 알림(ERROR 로그)으로 받는다
 *
 * 매일 05:10, 09:10 KST: 사람이 처리해야 하는(MANUAL_REQUIRED) 정산서가 남아 있으면 건수를 ERROR로 남긴다.
 *   05:10은 월 정산 정책(10일 05:00까지 모든 정산서를 PAID, MANUAL_REQUIRED, HOLD 중 하나로)의 결과를 바로 알리기 위해서다.
 * 강제 종료로 멈춘 월 정산 알림은 기동 시 SettlementJobRecoveryRunner가 ERROR로 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementOperationAlertScheduler {

    private final SettlementFacade settlementFacade;

    @Scheduled(cron = "0 10 5,9 * * *", zone = "Asia/Seoul")
    public void alertManualRequired() {
        try {
            long manualRequired = settlementFacade.countManualRequired();
            if (manualRequired > 0) {
                log.error("수동 처리 필요(MANUAL_REQUIRED) 정산서 {}건이 남아 있습니다. 관리자 정산 화면의 처리 필요 탭에서 확인해 주세요",
                        manualRequired);
            }
        } catch (Exception e) {
            log.error("정산 운영 알림 점검 실패", e);
        }
    }
}
