package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.in.dto.*;
import com.backend.global.page.PageResponse;
import com.backend.shared.order.dto.ConfirmedOrderItemDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/**
 * 정산 컨텍스트의 유일한 입구
 *
 * in(리스너·DataInit·Adapter·컨트롤러·배치·스케줄러)은 이 클래스만 호출한다.
 * 구현 로직은 UseCase·Support에 두고 여기서는 트랜잭션 경계를 정해 위임만 한다.
 * 예외: 지급(payout·retry)과 Job 실행은 UseCase가 트랜잭션을 직접 나누므로 @Transactional을 붙이지 않는다.
 */
@Component
@RequiredArgsConstructor
public class SettlementFacade {

    private final SettlementSupport settlementSupport;
    private final SettlementCreateCandidatesUseCase settlementCreateCandidatesUseCase;
    private final SettlementAggregateUseCase settlementAggregateUseCase;
    private final SettlementPayoutUseCase settlementPayoutUseCase;
    private final SettlementRunMonthlyJobUseCase settlementRunMonthlyJobUseCase;
    private final SettlementGetRunsUseCase settlementGetRunsUseCase;
    private final SettlementHoldUseCase settlementHoldUseCase;
    private final SettlementReleaseUseCase settlementReleaseUseCase;
    private final SettlementHoldSellerUseCase settlementHoldSellerUseCase;

    // ================= 정산 후보 생성 (구매 확정 이벤트) =================

    /** 구매 확정된 주문 항목마다 정산 후보 생성. @return 새로 만든 후보 수 */
    @Transactional
    public int createCandidates(Long orderId, LocalDateTime confirmedAt, List<ConfirmedOrderItemDto> items) {
        return settlementCreateCandidatesUseCase.createCandidates(orderId, confirmedAt, items);
    }

    @Transactional(readOnly = true)
    public boolean hasAnyCandidate() {
        return settlementSupport.hasAnyCandidate();
    }

    // ================= 월 정산 Job: 집계 Step =================

    @Transactional(readOnly = true)
    public List<Long> findAggregatableSellerIds(YearMonth targetMonth, Long afterSellerId, int limit) {
        return settlementSupport.findAggregatableSellerIds(targetMonth, afterSellerId, limit);
    }

    /** 판매자 1명 = 트랜잭션 1개 */
    @Transactional
    public int aggregateSeller(Long sellerId, YearMonth targetMonth, Long batchExecutionId, SettlementTrigger trigger) {
        return settlementAggregateUseCase.aggregateSeller(sellerId, targetMonth, batchExecutionId, trigger);
    }

    /** 집계가 롤백된 뒤 새 트랜잭션에서 실패 이력만 남긴다 */
    @Transactional
    public void recordAggregateFailure(Long sellerId, YearMonth targetMonth, Long batchExecutionId,
                                       SettlementTrigger trigger, String errorCode, String reason) {
        settlementAggregateUseCase.recordFailure(sellerId, targetMonth, batchExecutionId, trigger, errorCode, reason);
    }

    // ================= 지급 (월 정산 Job 지급 Step·재시도 스케줄러·관리자 재처리) =================

    @Transactional(readOnly = true)
    public List<Long> findMonthlyPayoutTargetIds(YearMonth targetMonth, Long afterSettlementId, int limit) {
        return settlementSupport.findMonthlyPayoutTargetIds(targetMonth, afterSettlementId, limit);
    }

    @Transactional(readOnly = true)
    public List<Long> findRetryTargetIds(int limit) {
        return settlementSupport.findRetryTargetIds(LocalDateTime.now(), limit);
    }

    /** 지급 트랜잭션과 실패 기록 트랜잭션은 UseCase가 TransactionTemplate으로 연다 */
    public SettlementPayoutResult payout(Long settlementId, SettlementTrigger trigger, Long batchExecutionId) {
        return settlementPayoutUseCase.payout(settlementId, trigger, batchExecutionId, null);
    }

    /** 관리자 수동 재처리(MANUAL_REQUIRED만, 사유 필수) */
    public SettlementPayoutResult retry(Long settlementId, String reason) {
        return settlementPayoutUseCase.retryManually(settlementId, reason);
    }

    // ================= 월 정산 Job 실행·조회 =================

    /** Job이 Step마다 트랜잭션을 관리하므로 여기서는 트랜잭션을 열지 않는다 */
    public SettlementRunResponse runMonthlyJob(YearMonth targetMonth, SettlementTrigger trigger) {
        return settlementRunMonthlyJobUseCase.run(targetMonth, trigger);
    }

    public PageResponse<SettlementRunResponse> getRuns(int page, int size) {
        return settlementGetRunsUseCase.getRuns(page, size);
    }

    public boolean isMonthlyJobRunning() {
        return settlementGetRunsUseCase.isRunning();
    }

    // ================= 관리자 조회 =================

    @Transactional(readOnly = true)
    public PageResponse<SettlementResponse> getSettlements(String month, SettlementStatus status, Long sellerId,
                                                           Pageable pageable) {
        return PageResponse.from(settlementSupport.findSettlements(month, status, sellerId, pageable),
                SettlementResponse::from);
    }

    @Transactional(readOnly = true)
    public SettlementDetailResponse getSettlement(Long settlementId) {
        Settlement settlement = settlementSupport.getSettlement(settlementId);
        return SettlementDetailResponse.of(settlement, settlementSupport.findCandidatesOf(settlementId));
    }

    /** 정산서 1건의 처리 이력(시도 순). 없는 정산서는 404 */
    @Transactional(readOnly = true)
    public List<SettlementHistoryResponse> getHistories(Long settlementId) {
        settlementSupport.getSettlement(settlementId);
        return settlementSupport.findSettlementHistories(settlementId).stream()
                .map(SettlementHistoryResponse::from)
                .toList();
    }

    // ================= 관리자 보류·해제 =================

    @Transactional
    public SettlementResponse hold(Long settlementId, String reason) {
        return settlementHoldUseCase.hold(settlementId, reason);
    }

    @Transactional
    public SettlementResponse release(Long settlementId) {
        return settlementReleaseUseCase.release(settlementId);
    }

    // ================= 다른 컨텍스트 제공 (SettlementApiAdapter) =================

    /** 미정산 금액. 회원 컨텍스트 트랜잭션 안에서 불리면 그 트랜잭션에 참여한다 */
    @Transactional(readOnly = true)
    public long getUnsettledAmount(Long sellerId) {
        return settlementSupport.sumUnsettledPayout(sellerId);
    }

    /** 판매자 제재: 회원 컨텍스트 트랜잭션(REQUIRED)에 참여해 함께 커밋·롤백된다 */
    @Transactional
    public int holdSeller(Long sellerId, String reason) {
        return settlementHoldSellerUseCase.holdSeller(sellerId, reason);
    }
}