package com.backend.boundedContext.payment.app;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.domain.PaymentPolicy;
import com.backend.boundedContext.payment.domain.TopupStatus;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.boundedContext.payment.out.TopupStuckCount;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 오래 남은 충전 건 경고: 결과가 정해지지 않은 상태로 topupStuckWarnAfter(1시간)가 넘게 남은 충전 건을 상태별로 세어 오류 로그를 남김
 * 대사가 계속 결과를 정하지 못하는 건(PG 장애가 길어짐, 자동으로 정할 수 없어 사람 확인 대기 등)을 드러내기 위함
 * 이런 건이 남아 있으면 회원 탈퇴가 막히고(hasPendingTopups) 대사 배치 자리를 차지함
 *
 * 기준은 마지막 수정 시각(updatedAt): PROCESSING·CANCELING은 상태가 바뀐 시각, METHOD_CANCELING은 마지막 취소 재시도 시각
 * 오류 로그는 실행마다 상태별로 한 줄이고, Grafana는 같은 알림을 1시간마다 다시 보냄 (남아 있는 동안 1시간마다 알림, 정리되면 멈춤)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentWarnStuckTopupsUseCase {

    private static final Set<TopupStatus> UNSETTLED_STATUSES = EnumSet.of(TopupStatus.PROCESSING, TopupStatus.CANCELING, TopupStatus.METHOD_CANCELING);

    private final PaymentPolicy paymentPolicy;
    private final TopupRepository topupRepository;

    // 오래 남은 충전 건을 상태별로 오류 로그로 남기고, 전체 건수를 반환
    public long warnStuckTopups(LocalDateTime now) {
        LocalDateTime cutoff = now.minus(paymentPolicy.topupStuckWarnAfter());
        List<TopupStuckCount> stuckCounts = topupRepository.countStale(UNSETTLED_STATUSES, cutoff);

        long total = 0;
        for (TopupStuckCount stuck : stuckCounts) {
            log.error("결과가 정해지지 않은 충전 건이 {}분 넘게 남아 있습니다. 확인이 필요합니다. status={}, 건수={}, 가장 작은 topupId={}",
                    paymentPolicy.topupStuckWarnAfter().toMinutes(), stuck.status(), stuck.count(), stuck.minTopupId());
            total += stuck.count();
        }
        return total;
    }
}
