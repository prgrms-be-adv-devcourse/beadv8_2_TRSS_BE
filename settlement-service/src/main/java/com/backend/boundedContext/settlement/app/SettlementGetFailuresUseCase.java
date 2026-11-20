package com.backend.boundedContext.settlement.app;

import com.backend.boundedContext.settlement.domain.Settlement;
import com.backend.boundedContext.settlement.domain.SettlementStatus;
import com.backend.boundedContext.settlement.out.SettlementRepository;
import com.backend.global.page.PageResponse;
import com.backend.shared.admin.dto.AdminFailureItem;
import com.backend.shared.admin.dto.AdminFailureTargetType;
import com.backend.shared.admin.dto.AdminFailureType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 관리자 실패 거래 화면(A7)의 정산 행: 수동 처리가 필요한(MANUAL_REQUIRED) 정산서
 * 공통 형식(AdminFailureItem)으로 돌려준다. 결제, 충전, 환불, 배송도 같은 형식으로 각자 돌려주고 화면이 합친다
 *
 * occurredAt = 정산서가 마지막으로 바뀐 시각(수동 처리 필요가 된 시각), 기간은 그 날짜로 거른다(from, to 포함, 한국 시간)
 * 처리는 관리자 월 정산 화면의 재처리라서 handlingCode는 MONTHLY_SETTLEMENT다
 */
@Component
@RequiredArgsConstructor
public class SettlementGetFailuresUseCase {

    static final String HANDLING_CODE = "MONTHLY_SETTLEMENT";
    static final String HANDLING_LABEL = "월 정산에서 재처리";
    private static final LocalDateTime EARLIEST = LocalDateTime.of(2000, 1, 1, 0, 0);
    private static final LocalDateTime LATEST = LocalDateTime.of(9999, 1, 1, 0, 0);

    private final SettlementRepository settlementRepository;
    private final SettlementSupport settlementSupport;

    public PageResponse<AdminFailureItem> getFailures(LocalDate from, LocalDate to, Pageable pageable) {
        LocalDateTime fromAt = from == null ? EARLIEST : from.atStartOfDay();
        LocalDateTime toAt = to == null ? LATEST : to.plusDays(1).atStartOfDay();
        // 요청의 sort는 무시하고 최신순으로만 준다(다른 서비스 결과와 합쳐 정렬하기 쉽게)
        Pageable latestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "updatedAt").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<Settlement> page = settlementRepository.findByStatusUpdatedBetween(SettlementStatus.MANUAL_REQUIRED,
                fromAt, toAt, latestFirst);
        Map<Long, String> names = settlementSupport.findBusinessNames(
                page.getContent().stream().map(Settlement::getSellerId).distinct().toList());
        return PageResponse.from(page, s -> toItem(s, names.get(s.getSellerId())));
    }

    static AdminFailureItem toItem(Settlement settlement, String businessName) {
        return new AdminFailureItem(
                AdminFailureType.SETTLEMENT_MANUAL_REQUIRED,
                settlement.getUpdatedAt(),
                AdminFailureTargetType.SELLER,
                settlement.getSellerId(),
                businessName,
                settlement.getPayoutAmount(),
                SettlementStatus.MANUAL_REQUIRED.name(),
                reasonLabelOf(settlement),
                HANDLING_CODE,
                HANDLING_LABEL,
                settlement.getId(),
                null);
    }

    /**
     * 원인 칸: 마지막 실패 사유(없으면 "수동 처리 필요") 뒤에 시도 횟수를 붙인다. 예) "홀딩의 남은 금액이 지급액보다 적습니다. (시도 1번)"
     * 사유를 먼저 보여 줘야 관리자가 무엇을 고칠지 안다. 수동 처리 필요가 되는 두 경로(markFailed, markManualRequired)가
     * 모두 시도 횟수를 1 올리므로 이 목록은 늘 1번 이상이다.
     * retryCount는 첫 시도와 관리자 재처리까지 센 지급 시도 횟수라 "재시도"가 아니라 "시도"로 쓴다
     */
    private static String reasonLabelOf(Settlement settlement) {
        String reason = settlement.getLastFailReason();
        String label = reason == null || reason.isBlank() ? "수동 처리 필요" : reason;
        int attempts = settlement.getRetryCount();
        return attempts > 0 ? "%s (시도 %d번)".formatted(label, attempts) : label;
    }
}
