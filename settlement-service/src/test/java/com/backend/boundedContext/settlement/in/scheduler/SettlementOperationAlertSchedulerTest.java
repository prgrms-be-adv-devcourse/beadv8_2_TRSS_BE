package com.backend.boundedContext.settlement.in.scheduler;

import com.backend.boundedContext.settlement.app.SettlementFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 정산 운영 알림: MANUAL_REQUIRED 정산서가 남아 있으면 매일 05:10, 09:10 KST에 ERROR 로그를 남긴다
 */
@ExtendWith(OutputCaptureExtension.class)
class SettlementOperationAlertSchedulerTest {

    private final SettlementFacade settlementFacade = mock(SettlementFacade.class);
    private final SettlementOperationAlertScheduler scheduler = new SettlementOperationAlertScheduler(settlementFacade);

    @Test
    @DisplayName("MANUAL_REQUIRED 정산서가 있으면 건수를 ERROR 로그로 남긴다")
    void manualRequired_logsError(CapturedOutput output) {
        given(settlementFacade.countManualRequired()).willReturn(3L);

        scheduler.alertManualRequired();

        assertThat(output.getOut()).contains("ERROR").contains("수동 처리 필요(MANUAL_REQUIRED) 정산서 3건");
    }

    @Test
    @DisplayName("MANUAL_REQUIRED 정산서가 없으면 알림을 남기지 않는다")
    void none_noLog(CapturedOutput output) {
        given(settlementFacade.countManualRequired()).willReturn(0L);

        scheduler.alertManualRequired();

        assertThat(output.getOut()).doesNotContain("수동 처리 필요(MANUAL_REQUIRED)");
    }

    @Test
    @DisplayName("건수 조회가 실패해도 예외를 밖으로 던지지 않고 ERROR 로그로 남긴다(스케줄러가 멈추지 않게)")
    void countFails_logsError(CapturedOutput output) {
        given(settlementFacade.countManualRequired()).willThrow(new IllegalStateException("DB 연결 실패"));

        scheduler.alertManualRequired();

        assertThat(output.getOut()).contains("ERROR").contains("정산 운영 알림 점검 실패");
    }

    @Test
    @DisplayName("실행 시각은 매일 05:10, 09:10 KST다")
    void runsAt0510And0910Kst() throws Exception {
        Scheduled scheduled = SettlementOperationAlertScheduler.class.getMethod("alertManualRequired")
                .getAnnotation(Scheduled.class);
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
        CronExpression cron = CronExpression.parse(scheduled.cron());
        ZoneId kst = ZoneId.of("Asia/Seoul");

        ZonedDateTime first = cron.next(ZonedDateTime.of(LocalDateTime.of(2026, 12, 10, 0, 0), kst));
        ZonedDateTime second = cron.next(first);
        ZonedDateTime third = cron.next(second);

        assertThat(first.toLocalDateTime()).isEqualTo(LocalDateTime.of(2026, 12, 10, 5, 10));
        assertThat(second.toLocalDateTime()).isEqualTo(LocalDateTime.of(2026, 12, 10, 9, 10));
        assertThat(third.toLocalDateTime()).isEqualTo(LocalDateTime.of(2026, 12, 11, 5, 10));
    }
}
