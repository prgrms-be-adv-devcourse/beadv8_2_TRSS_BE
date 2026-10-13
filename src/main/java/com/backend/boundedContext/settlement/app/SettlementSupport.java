package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementCandidate;
import com.backend.boundedContext.settlement.domain.SettlementErrorCode;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.out.SettlementCandidateRepository;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import com.backend.global.exception.DomainException;
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
import java.util.List;

/**
 * 여러 UseCase와 Facade가 함께 쓰는 조회 모음. Facade는 Repository를 직접 알지 않고 이 클래스를 거친다.
 * 트랜잭션은 호출한 Facade·UseCase의 것을 따른다.
 */
@Component
@RequiredArgsConstructor
public class SettlementSupport {

    private final SettlementRepository settlementRepository;
    private final SettlementCandidateRepository settlementCandidateRepository;

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
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Order.desc("id"))));
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