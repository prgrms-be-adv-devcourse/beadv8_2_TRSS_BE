package com.backend.boundedContext.settlement.in.batch;

import com.backend.boundedContext.settlement.app.SettlementFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 앱이 뜰 때 강제 종료로 STARTED에 멈춘 월 정산 실행을 정리한다(SettlementRecoverJobUseCase)
 * 정리에 실패해도 앱 기동은 막지 않는다(관리자 화면의 실행 기록에서 확인하고 다시 실행)
 * DataInit(@Order 5)보다 먼저 돌도록 순서를 앞에 둔다
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class SettlementJobRecoveryRunner implements ApplicationRunner {

    private final SettlementFacade settlementFacade;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int recovered = settlementFacade.recoverInterruptedJobs();
            if (recovered > 0) log.warn("중단된 월 정산 실행 {}건을 다시 실행할 수 있게 정리함", recovered);
        } catch (Exception e) {
            log.error("중단된 월 정산 실행 정리 실패", e);
        }
    }
}
