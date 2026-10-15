package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementCandidateStatus;
import com.backend.boundedContext.settlement.domain.SettlementErrorCode;
import com.backend.boundedContext.settlement.domain.SettlementHistoryTargetType;
import com.backend.boundedContext.settlement.domain.SettlementProcessHistory;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import com.backend.boundedContext.settlement.out.SettlementProcessHistoryRepository;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.out.MemberApi;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 여러 UseCase와 Facade가 함께 쓰는 조회 모음. Facade는 Repository를 직접 알지 않고 이 클래스를 거친다.
 * 트랜잭션은 호출한 Facade·UseCase의 것을 따른다.
 */
@Component
@RequiredArgsConstructor
public class SettlementSupport {

    /** 최신순: 정산월이 같으면 나중에 만든 것부터 */
    private static final Sort LATEST = Sort.by(Sort.Order.desc("id"));
    private static final Sort SELLER_LATEST = Sort.by(Sort.Order.desc("settlementMonth"), Sort.Order.desc("id"));

    private final SettlementRepository settlementRepository;
    private final SettlementCandidateRepository settlementCandidateRepository;
    private final SettlementProcessHistoryRepository settlementProcessHistoryRepository;
    private final MemberApi memberApi;

    // ---------- 정산 후보 ----------

    public boolean hasAnyCandidate() {
        return settlementCandidateRepository.count() > 0;
    }

    public List<SettlementCandidate> findCandidatesOf(Long settlementId) {
        return settlementCandidateRepository.findBySettlementIdOrderByIdAsc(settlementId);
    }

    // ---------- 정산서 ----------

    public Settlement getSettlement(Long settlementId) {
        return settlementRepository.findById(settlementId)
                .orElseThrow(() -> new DomainException(SettlementErrorCode.SETTLEMENT_NOT_FOUND));
    }

    /** 상태를 바꾸기 전에 쓰는 잠금 조회 */
    public Settlement getSettlementWithLock(Long settlementId) {
        return settlementRepository.findWithLockById(settlementId)
                .orElseThrow(() -> new DomainException(SettlementErrorCode.SETTLEMENT_NOT_FOUND));
    }

    /** 관리자 목록. 조건은 모두 선택(null이면 그 조건을 빼고 조회), 최신순 */
    public Page<Settlement> findSettlements(String month, SettlementStatus status, Long sellerId, Pageable pageable) {
        List<Specification<Settlement>> specs = new ArrayList<>();
        if (month != null) specs.add((root, query, cb) -> cb.equal(root.get("settlementMonth"), month));
        if (status != null) specs.add((root, query, cb) -> cb.equal(root.get("status"), status));
        if (sellerId != null) specs.add((root, query, cb) -> cb.equal(root.get("sellerId"), sellerId));
        return settlementRepository.findAll(Specification.allOf(specs),
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST));
    }

    // ---------- 판매자 본인 정산서 ----------

    /**
     * 로그인 회원의 판매자 ID(seller.id). 판매자 신청 기록이 없으면 empty
     * 정산서 조회는 지난 정산 내역을 보는 기능이라 승인 상태(APPROVED)를 요구하지 않는다(제재·철회된 판매자도 자기 내역은 본다)
     */
    public Optional<Long> findSellerId(Long memberId) {
        return memberApi.findSellerByMemberId(memberId).map(SellerDto::sellerId);
    }

    /** 판매자 본인 정산서. 남의 정산서·없는 정산서는 모두 404 (존재 여부를 숨긴다) */
    public Settlement getSellerSettlement(Long settlementId, Long sellerId) {
        return settlementRepository.findByIdAndSellerId(settlementId, sellerId)
                .orElseThrow(() -> new DomainException(SettlementErrorCode.SETTLEMENT_NOT_FOUND));
    }

    /** 판매자 본인 정산서 목록. month·statuses는 선택(null이면 그 조건을 빼고 조회), 최근 정산월부터 */
    public Page<Settlement> findSellerSettlements(Long sellerId, String month, Collection<SettlementStatus> statuses,
                                                  Pageable pageable) {
        List<Specification<Settlement>> specs = new ArrayList<>();
        specs.add((root, query, cb) -> cb.equal(root.get("sellerId"), sellerId));
        if (month != null) specs.add((root, query, cb) -> cb.equal(root.get("settlementMonth"), month));
        if (statuses != null) specs.add((root, query, cb) -> root.get("status").in(statuses));
        return settlementRepository.findAll(Specification.allOf(specs),
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), SELLER_LATEST));
    }

    // ---------- 관리자 정산 후보 ----------

    /** 관리자 후보 목록. 조건은 모두 선택(null이면 그 조건을 빼고 조회), 최신순 */
    public Page<SettlementCandidate> findCandidates(Long sellerId, String month, SettlementCandidateStatus status,
                                                    Pageable pageable) {
        List<Specification<SettlementCandidate>> specs = new ArrayList<>();
        if (sellerId != null) specs.add((root, query, cb) -> cb.equal(root.get("sellerId"), sellerId));
        if (month != null) specs.add((root, query, cb) -> cb.equal(root.get("settlementMonth"), month));
        if (status != null) specs.add((root, query, cb) -> cb.equal(root.get("status"), status));
        return settlementCandidateRepository.findAll(Specification.allOf(specs),
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST));
    }

    /** 상태를 바꾸기 전에 쓰는 후보 잠금 조회 */
    public SettlementCandidate getCandidateWithLock(Long candidateId) {
        return settlementCandidateRepository.findWithLockById(candidateId)
                .orElseThrow(() -> new DomainException(SettlementErrorCode.SETTLEMENT_CANDIDATE_NOT_FOUND));
    }

    // ---------- 처리 이력 ----------

    /** 정산서 1건의 처리 이력(집계·지급·보류·해제)을 처리 순서대로 */
    public List<SettlementProcessHistory> findSettlementHistories(Long settlementId) {
        return settlementProcessHistoryRepository.findByTargetTypeAndTargetIdOrderByIdAsc(
                SettlementHistoryTargetType.SETTLEMENT, settlementId);
    }

    // ---------- 미정산 금액 ----------

    /** 미정산 금액(지급액 합). 후보가 없으면 0 */
    public long sumUnsettledPayout(Long sellerId) {
        Long sum = settlementCandidateRepository.sumUnsettledPayout(sellerId);
        return sum == null ? 0L : sum;
    }

    // ---------- 배치·스케줄러 대상 ----------

    public List<Long> findAggregatableSellerIds(YearMonth targetMonth, Long afterSellerId, int limit) {
        return settlementCandidateRepository.findAggregatableSellerIds(targetMonth.toString(), afterSellerId,
                PageRequest.of(0, limit));
    }

    public List<Long> findMonthlyPayoutTargetIds(YearMonth targetMonth, Long afterSettlementId, int limit) {
        return settlementRepository.findMonthlyPayoutTargetIds(targetMonth.toString(), afterSettlementId,
                PageRequest.of(0, limit));
    }

    public List<Long> findRetryTargetIds(LocalDateTime now, int limit) {
        return settlementRepository.findRetryTargetIds(now, PageRequest.of(0, limit));
    }
}