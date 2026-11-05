package com.backend.boundedContext.settlement.domain;

import java.util.Arrays;
import java.util.List;

/**
 * 판매자 화면에 보여 주는 정산서 상태
 *
 * 판매자에게는 내부 처리 상태(FAILED·MANUAL_REQUIRED, 재시도 횟수, 실패 사유)를 보여 주지 않는다.
 * 실패·재처리 중인 정산서는 "지급 지연"으로만 보여 주고, 사유는 응답에 넣지 않는다.
 * 정산서 상태(SettlementStatus) 1개는 판매자 상태 1개에만 속한다.
 */
public enum SettlementSellerStatus {

    /** 집계 완료, 지급 대기 */
    SCHEDULED("지급 예정", List.of(SettlementStatus.READY)),
    /** 지급 완료 */
    PAID("지급 완료", List.of(SettlementStatus.PAID)),
    /** 지급 실패 후 재시도 중이거나 관리자 재처리 대기. 내부 사유는 숨긴다 */
    DELAYED("지급 지연", List.of(SettlementStatus.FAILED, SettlementStatus.MANUAL_REQUIRED)),
    /** 관리자 보류. 보류 사유는 숨긴다 */
    ON_HOLD("지급 보류", List.of(SettlementStatus.HOLD));

    private final String label;
    private final List<SettlementStatus> statuses;

    SettlementSellerStatus(String label, List<SettlementStatus> statuses) {
        this.label = label;
        this.statuses = statuses;
    }

    public String getLabel() {
        return label;
    }

    /** 이 판매자 상태로 거를 때 조회할 정산서 상태 목록 */
    public List<SettlementStatus> getStatuses() {
        return statuses;
    }

    /** 정산서 상태 → 판매자 화면 상태 */
    public static SettlementSellerStatus of(SettlementStatus status) {
        return Arrays.stream(values())
                .filter(sellerStatus -> sellerStatus.statuses.contains(status))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("판매자 상태로 바꿀 수 없는 정산서 상태: " + status));
    }
}