package com.backend.boundedContext.settlement.domain;

import java.util.List;

/**
 * 판별 결과 (판매자 1명 단위)
 *
 * PASS: 확정·지급 진행 / HOLD: 정산서를 HOLD로 만든다(후보는 묶고 지급만 보류) /
 * BLOCK: 이번 실행에서 확정하지 않는다(후보는 READY로 남고 관리자가 확인)
 *
 * 판별 Step이 Job 실행 컨텍스트에 남기고 확정 Step이 읽으므로, 문자열로 바꾸고 되돌리는 방법을 함께 둔다.
 */
public record SettlementJudgement(Type type, String errorCode, String reason, List<Long> rejectedCandidateIds) {

    public enum Type { PASS, HOLD, BLOCK }

    private static final String SEPARATOR = "::";

    public SettlementJudgement {
        rejectedCandidateIds = rejectedCandidateIds == null ? List.of() : List.copyOf(rejectedCandidateIds);
    }

    public static SettlementJudgement pass() {
        return new SettlementJudgement(Type.PASS, null, null, List.of());
    }

    public static SettlementJudgement hold(String reason) {
        return new SettlementJudgement(Type.HOLD, null, reason, List.of());
    }

    public static SettlementJudgement block(String errorCode, String reason, List<Long> rejectedCandidateIds) {
        return new SettlementJudgement(Type.BLOCK, errorCode, reason, rejectedCandidateIds);
    }

    public boolean isPass() {
        return type == Type.PASS;
    }

    /** Job 실행 컨텍스트 저장용: TYPE::errorCode::reason (후보 ID는 처리 이력에 남기므로 넣지 않는다) */
    public String encode() {
        return type + SEPARATOR + nullToEmpty(errorCode) + SEPARATOR + nullToEmpty(reason);
    }

    public static SettlementJudgement decode(String value) {
        String[] parts = value.split(SEPARATOR, 3);
        Type type = Type.valueOf(parts[0]);
        String errorCode = parts.length > 1 && !parts[1].isEmpty() ? parts[1] : null;
        String reason = parts.length > 2 && !parts[2].isEmpty() ? parts[2] : null;
        return new SettlementJudgement(type, errorCode, reason, List.of());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
