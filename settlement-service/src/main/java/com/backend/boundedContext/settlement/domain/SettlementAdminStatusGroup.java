package com.backend.boundedContext.settlement.domain;

import java.util.List;

/**
 * 관리자 정산 화면의 상태 탭
 *
 * 관리자에게는 "사람이 할 일이 있는지"로 묶어 보여 준다. 재시도 중(FAILED)과 수동 처리 필요(MANUAL_REQUIRED)는
 * 둘 다 아직 지급되지 않은 문제 건이라 "처리 필요" 한 탭으로 묶고, 행에서는 상태로 구분한다
 * (FAILED는 다음 재시도 일시가 있어 자동 처리 중, MANUAL_REQUIRED만 재처리 버튼).
 */
public enum SettlementAdminStatusGroup {

    /** 처리 필요: 재시도 중 + 수동 처리 필요 */
    ACTION_REQUIRED("처리 필요", List.of(SettlementStatus.FAILED, SettlementStatus.MANUAL_REQUIRED)),
    /** 지급 예정 */
    SCHEDULED("지급 예정", List.of(SettlementStatus.READY, SettlementStatus.PROCESSING)),
    /** 지급 완료 */
    PAID("지급 완료", List.of(SettlementStatus.PAID)),
    /** 보류 (제재·원장 불일치·관리자 보류) */
    ON_HOLD("보류", List.of(SettlementStatus.HOLD));

    private final String label;
    private final List<SettlementStatus> statuses;

    SettlementAdminStatusGroup(String label, List<SettlementStatus> statuses) {
        this.label = label;
        this.statuses = statuses;
    }

    public String getLabel() {
        return label;
    }

    public List<SettlementStatus> getStatuses() {
        return statuses;
    }
}
