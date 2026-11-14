package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.SettlementAdminStatusGroup;
import com.backend.boundedContext.settlement.domain.SettlementCandidateStatus;
import com.backend.boundedContext.settlement.domain.SettlementJudgement;
import com.backend.boundedContext.settlement.domain.SettlementLedgerCheckResult;
import com.backend.boundedContext.settlement.domain.SettlementSellerStatus;
import com.backend.boundedContext.settlement.domain.SettlementPolicy;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.domain.SettlementTrigger;
import com.backend.boundedContext.settlement.in.dto.SettlementLedgerMismatchConfirmResponse;
import com.backend.boundedContext.settlement.in.dto.SellerSettlementDetailResponse;
import com.backend.boundedContext.settlement.in.dto.SellerSettlementResponse;
import com.backend.boundedContext.settlement.in.dto.SettlementCandidateResponse;
import com.backend.boundedContext.settlement.in.dto.SettlementDetailResponse;
import com.backend.boundedContext.settlement.in.dto.SettlementExpectedResponse;
import com.backend.boundedContext.settlement.in.dto.SettlementExportFile;
import com.backend.boundedContext.settlement.in.dto.SettlementRunnableMonthResponse;
import com.backend.boundedContext.settlement.in.dto.SettlementSummaryResponse;
import com.backend.boundedContext.settlement.in.dto.SellerSettlementSummaryResponse;
import com.backend.boundedContext.settlement.in.dto.SettlementHistoryResponse;
import com.backend.boundedContext.settlement.in.dto.SettlementPayoutResult;
import com.backend.boundedContext.settlement.in.dto.SettlementPreviewResponse;
import com.backend.boundedContext.settlement.in.dto.SettlementResponse;
import com.backend.boundedContext.settlement.in.dto.SettlementRunResponse;
import com.backend.global.page.PageResponse;
import com.backend.shared.order.dto.ConfirmedOrderItemDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

/**
 * 정산 컨텍스트의 유일한 입구
 *
 * in(리스너·DataInit·Adapter·컨트롤러·배치·스케줄러)은 이 클래스만 호출한다.
 * 구현 로직은 UseCase·Support에 두고 여기서는 트랜잭션 경계를 정해 위임만 한다.
 * 예외: 지급(payout·retry), Job 실행, 엑셀 다운로드(export)는 UseCase가 트랜잭션을 직접 나누므로 @Transactional을 붙이지 않는다.
 */
@Component
@RequiredArgsConstructor
public class SettlementFacade {

    private final SettlementSupport settlementSupport;
    private final SettlementPolicy settlementPolicy;
    private final SettlementCreateCandidatesUseCase settlementCreateCandidatesUseCase;
    private final SettlementAggregateUseCase settlementAggregateUseCase;
    private final SettlementPayoutUseCase settlementPayoutUseCase;
    private final SettlementRunMonthlyJobUseCase settlementRunMonthlyJobUseCase;
    private final SettlementGetRunsUseCase settlementGetRunsUseCase;
    private final SettlementGetSettlementListUseCase settlementGetSettlementListUseCase;
    private final SettlementGetSettlementDetailUseCase settlementGetSettlementDetailUseCase;
    private final SettlementGetSellerSettlementsUseCase settlementGetSellerSettlementsUseCase;
    private final SettlementGetSellerSettlementUseCase settlementGetSellerSettlementUseCase;
    private final SettlementGetExpectedAmountsUseCase settlementGetExpectedAmountsUseCase;
    private final SettlementGetPreviewUseCase settlementGetPreviewUseCase;
    private final SettlementHoldUseCase settlementHoldUseCase;
    private final SettlementReleaseUseCase settlementReleaseUseCase;
    private final SettlementHoldSellerUseCase settlementHoldSellerUseCase;
    private final SettlementExcludeCandidateUseCase settlementExcludeCandidateUseCase;
    private final SettlementJudgeUseCase settlementJudgeUseCase;
    private final SettlementLedgerRecheckUseCase settlementLedgerRecheckUseCase;
    private final SettlementRecoverJobUseCase settlementRecoverJobUseCase;
    private final SettlementExportUseCase settlementExportUseCase;
    private final SettlementGetSummaryUseCase settlementGetSummaryUseCase;
    private final SettlementGetRunnableMonthsUseCase settlementGetRunnableMonthsUseCase;

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

    // ================= 원장 재대조 스케줄러 (매일 03:35) =================

    @Transactional(readOnly = true)
    public List<Long> findLedgerRecheckTargetIds(LocalDateTime since, Long afterSettlementId, int limit) {
        return settlementLedgerRecheckUseCase.findTargetIds(since, afterSettlementId, limit);
    }

    /** 원장 불일치 보류 정리 대상(원장 불일치 사유로 보류된 정산서가 있는 판매자) */
    @Transactional(readOnly = true)
    public List<Long> findSellerIdsWithLedgerMismatchHolds() {
        return settlementLedgerRecheckUseCase.findSellerIdsWithLedgerMismatchHolds();
    }

    /** 판매자 1명 = 트랜잭션 1개. 불일치가 없으면 원장 불일치 보류를 해제한다 */
    @Transactional
    public int releaseLedgerMismatchHoldsIfResolved(Long sellerId, SettlementTrigger trigger) {
        return settlementLedgerRecheckUseCase.releaseLedgerMismatchHoldsIfResolved(sellerId, trigger);
    }

    /** 원장 재대조 대상(지급일과 상관없이 불일치가 아직 풀리지 않은 정산서) */
    @Transactional(readOnly = true)
    public List<Long> findUnresolvedLedgerRecheckTargetIds() {
        return settlementLedgerRecheckUseCase.findUnresolvedTargetIds();
    }

    /** 정산서 1건 = 트랜잭션 1개. 재대조 이력을 남긴다 */
    @Transactional
    public SettlementLedgerCheckResult recheckLedger(Long settlementId, SettlementTrigger trigger,
                                                     Long batchExecutionId) {
        return settlementLedgerRecheckUseCase.recheck(settlementId, trigger, batchExecutionId);
    }

    // ================= 월 정산 Job: 판별 Step =================

    @Transactional(readOnly = true)
    public Set<Long> findLedgerMismatchSellerIds() {
        return settlementJudgeUseCase.findLedgerMismatchSellerIds();
    }

    /** 판매자 1명 = 트랜잭션 1개. 판정 이력을 남긴다 */
    @Transactional
    public SettlementJudgement judgeSeller(Long sellerId, YearMonth targetMonth, Set<Long> ledgerMismatchSellerIds,
                                           SettlementTrigger trigger, Long batchExecutionId) {
        return settlementJudgeUseCase.judgeSeller(sellerId, targetMonth, ledgerMismatchSellerIds, trigger,
                batchExecutionId);
    }

    // ================= 월 정산 Job: 확정(집계) Step =================

    @Transactional(readOnly = true)
    public List<Long> findAggregatableSellerIds(YearMonth targetMonth, Long afterSellerId, int limit) {
        return settlementSupport.findAggregatableSellerIds(targetMonth, afterSellerId, limit);
    }

    /** 판매자 1명 = 트랜잭션 1개. judgement가 null이면 판정 없이(제재 여부만 보고) 확정 */
    @Transactional
    public int aggregateSeller(Long sellerId, YearMonth targetMonth, Long batchExecutionId, SettlementTrigger trigger,
                               SettlementJudgement judgement) {
        return settlementAggregateUseCase.aggregateSeller(sellerId, targetMonth, batchExecutionId, trigger, judgement);
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

    /** 선점·결과 기록·실패 기록 트랜잭션은 UseCase가 TransactionTemplate으로 연다(결제 지급 요청은 트랜잭션 밖) */
    public SettlementPayoutResult payout(Long settlementId, SettlementTrigger trigger, Long batchExecutionId) {
        return settlementPayoutUseCase.payout(settlementId, trigger, batchExecutionId, null);
    }

    /** 지급 결과 재확인 대상: PROCESSING이 된 지 processingRecheckMinutes가 지난 정산서 */
    public List<Long> findProcessingOverdueIds(int limit) {
        return settlementSupport.findProcessingOverdueIds(
                LocalDateTime.now().minusMinutes(settlementPolicy.getProcessingRecheckMinutes()), limit);
    }

    /** 지급 결과 재확인. 결제 원장 조회는 트랜잭션 밖, 확정은 UseCase가 TransactionTemplate으로 연다 */
    public SettlementPayoutResult reconcileProcessing(Long settlementId) {
        return settlementPayoutUseCase.reconcileProcessing(settlementId);
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

    /** 앱 기동 시 강제 종료로 STARTED에 멈춘 실행을 FAILED로 정리한다. 트랜잭션은 Spring Batch JobRepository가 관리한다 */
    public int recoverInterruptedJobs() {
        return settlementRecoverJobUseCase.recoverInterruptedJobs();
    }

    // ================= 관리자 조회 =================

    @Transactional(readOnly = true)
    public PageResponse<SettlementResponse> getSettlements(String month, YearMonth from, YearMonth to,
                                                           SettlementStatus status, SettlementAdminStatusGroup group,
                                                           Long sellerId, Pageable pageable) {
        return settlementGetSettlementListUseCase.getSettlements(month, from, to, status, group, sellerId, pageable);
    }

    /** 관리자 요약 카드: 조회 조건의 상태별 건수·지급 완료 금액 */
    @Transactional(readOnly = true)
    public SettlementSummaryResponse getSummary(String month, YearMonth from, YearMonth to, Long sellerId) {
        return settlementGetSummaryUseCase.getSummary(month, from, to, sellerId);
    }

    /** 수동 실행 확인 창: 실패했거나 실행되지 않은 끝난 달. Spring Batch 메타 테이블만 읽는다 */
    public List<SettlementRunnableMonthResponse> getRunnableMonths() {
        return settlementGetRunnableMonthsUseCase.getRunnableMonths(LocalDateTime.now());
    }

    @Transactional(readOnly = true)
    public SettlementDetailResponse getSettlement(Long settlementId) {
        return settlementGetSettlementDetailUseCase.getSettlement(settlementId);
    }

    /** 정산서 1건의 처리 이력(시도 순). 없는 정산서는 404 */
    @Transactional(readOnly = true)
    public List<SettlementHistoryResponse> getHistories(Long settlementId) {
        settlementSupport.getSettlement(settlementId);
        return settlementSupport.findSettlementHistories(settlementId).stream()
                .map(SettlementHistoryResponse::from)
                .toList();
    }

    /** 정산월 미리보기(저장하지 않음, 단순 조회) */
    @Transactional(readOnly = true)
    public SettlementPreviewResponse getPreview(YearMonth targetMonth, Pageable pageable) {
        return settlementGetPreviewUseCase.getPreview(targetMonth, pageable);
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

    /** 관리자 원장 재대조 불일치 확인. 이력 저장과 자동 해제가 한 트랜잭션이다 */
    @Transactional
    public SettlementLedgerMismatchConfirmResponse confirmLedgerMismatch(Long settlementId, String reason) {
        return new SettlementLedgerMismatchConfirmResponse(settlementId,
                settlementLedgerRecheckUseCase.confirmMismatchByAdmin(settlementId, reason));
    }

    // ================= 관리자 정산 후보 =================

    @Transactional(readOnly = true)
    public PageResponse<SettlementCandidateResponse> getCandidates(Long sellerId, String month,
                                                                   SettlementCandidateStatus status,
                                                                   Pageable pageable) {
        return PageResponse.from(settlementSupport.findCandidates(sellerId, month, status, pageable),
                SettlementCandidateResponse::from);
    }

    @Transactional
    public SettlementCandidateResponse excludeCandidate(Long candidateId, String reason) {
        return settlementExcludeCandidateUseCase.exclude(candidateId, reason);
    }

    /** 판매자 요약 카드: 조회 기간 지급 완료 건수·금액, 가장 최근 지급 정산서 */
    @Transactional(readOnly = true)
    public SellerSettlementSummaryResponse getSellerSummary(Long memberId, YearMonth from, YearMonth to) {
        return settlementGetSummaryUseCase.getSellerSummary(memberId, from, to);
    }

    // ================= 엑셀 다운로드 (지급 완료 정산서) =================

    /**
     * 관리자 지급 완료 정산서 엑셀(전체, 판매자 ID로 거르기 가능)
     * 트랜잭션은 UseCase가 묶음마다 연다(파일을 만드는 동안 DB 연결을 계속 잡지 않게, 회원 서비스 조회는 트랜잭션 밖에서)
     */
    public SettlementExportFile exportSettlements(Long adminMemberId, YearMonth from, YearMonth to, Long sellerId) {
        return settlementExportUseCase.exportForAdmin(adminMemberId, from, to, sellerId);
    }

    /** 판매자 본인 지급 완료 정산서 엑셀. 트랜잭션은 UseCase가 묶음마다 연다 */
    public SettlementExportFile exportSellerSettlements(Long memberId, YearMonth from, YearMonth to) {
        return settlementExportUseCase.exportForSeller(memberId, from, to);
    }

    // ================= 판매자 본인 조회 =================

    @Transactional(readOnly = true)
    public PageResponse<SellerSettlementResponse> getSellerSettlements(Long memberId, String month, YearMonth from,
                                                                       YearMonth to, SettlementSellerStatus status,
                                                                       Pageable pageable) {
        return settlementGetSellerSettlementsUseCase.getSellerSettlements(memberId, month, from, to, status,
                pageable);
    }

    @Transactional(readOnly = true)
    public SellerSettlementDetailResponse getSellerSettlement(Long memberId, Long settlementId) {
        return settlementGetSellerSettlementUseCase.getSellerSettlement(memberId, settlementId);
    }

    @Transactional(readOnly = true)
    public SettlementExpectedResponse getExpectedAmounts(Long memberId) {
        return settlementGetExpectedAmountsUseCase.getExpectedAmounts(memberId);
    }

    // ================= 다른 컨텍스트 제공 (SettlementApiAdapter) =================

    /** 미정산 금액. 회원 컨텍스트 트랜잭션 안에서 불리면 그 트랜잭션에 참여한다 */
    @Transactional(readOnly = true)
    public long getUnsettledAmount(Long sellerId) {
        return settlementSupport.sumUnsettledPayout(sellerId);
    }

    /** 판매자 제재(SettlementApi.holdSeller): 회원 컨텍스트 트랜잭션(REQUIRED)에 참여해 함께 커밋·롤백된다
     * TODO #225에서 회원이 직접 호출을 빼면 SettlementApi.holdSeller와 함께 지운다 */
    @Transactional
    public int holdSeller(Long sellerId, String reason) {
        return settlementHoldSellerUseCase.holdSeller(sellerId, reason, SettlementTrigger.ADMIN);
    }

    /** 판매자 제재 이벤트(SellerSanctionedEvent): 컨슈머 트랜잭션(REQUIRED)에 참여해 처리 기록과 함께 커밋·롤백된다 */
    @Transactional
    public int holdSellerBySanctionEvent(Long sellerId, String reason) {
        return settlementHoldSellerUseCase.holdSeller(sellerId, reason, SettlementTrigger.EVENT);
    }
}