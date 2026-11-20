package com.backend.shared.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 관리자 실패 거래 화면(A7)의 한 행. 결제, 충전, 환불, 정산, 배송이 같은 모양으로 돌려준다
 *
 * 화면은 서비스마다 GET /api/v1/admin/{서비스}/failures를 불러 이 항목을 그대로 합쳐 한 표로 보여 준다.
 * 그래서 서비스마다 필드 의미를 똑같이 맞춘다(아래 설명). 목록은 occurredAt 내림차순(최신순)으로 준다.
 *
 * @param type          실패 유형
 * @param occurredAt    실패가 생긴(또는 지금 상태가 된) 시각
 * @param targetType    대상 종류 (회원, 판매자)
 * @param targetId      회원 ID 또는 판매자 ID
 * @param targetLabel   화면 표시용 대상 이름. 회원은 이메일 마스킹(maskEmail), 판매자는 상호명. 모르면 null
 * @param amount        금액(원). 금액이 없는 유형(배송 추적 실패)은 null
 * @param reasonCode    원인 코드 (상태값이나 오류 코드)
 * @param reasonLabel   표의 "원인" 칸 문구
 * @param handlingCode  처리 방법 코드
 * @param handlingLabel 표의 "처리" 칸 문구
 * @param refId         원본 ID (결제 ID, 충전 ID, 환불 ID, 정산서 ID, 배송 ID)
 * @param orderId       관련 주문 ID. 없으면 null
 */
@Schema(description = "관리자 실패 거래 1건. 결제, 충전, 환불, 정산, 배송이 같은 모양으로 돌려준다")
public record AdminFailureItem(
        @Schema(description = "실패 유형", example = "PAYMENT_FAILED")
        AdminFailureType type,
        @Schema(description = "실패가 생긴(또는 지금 상태가 된) 시각", example = "2026-11-09T14:20:00")
        LocalDateTime occurredAt,
        @Schema(description = "대상 종류", example = "MEMBER")
        AdminFailureTargetType targetType,
        @Schema(description = "회원 ID 또는 판매자 ID", example = "3")
        Long targetId,
        @Schema(description = "화면 표시용 대상 이름. 회원은 이메일 마스킹, 판매자는 상호명. 모르면 null", example = "bu***@palette.test")
        String targetLabel,
        @Schema(description = "금액(원). 금액이 없는 유형은 null", example = "37800")
        Long amount,
        @Schema(description = "원인 코드", example = "FAILED_SYSTEM_ERROR")
        String reasonCode,
        @Schema(description = "원인 문구", example = "시스템 오류")
        String reasonLabel,
        @Schema(description = "처리 방법 코드", example = "BUYER_CAN_RETRY")
        String handlingCode,
        @Schema(description = "처리 문구", example = "구매자가 다시 결제할 수 있어요")
        String handlingLabel,
        @Schema(description = "원본 ID (결제, 충전, 환불, 정산서, 배송 ID)", example = "12")
        Long refId,
        @Schema(description = "관련 주문 ID. 없으면 null", example = "142")
        Long orderId
) {

    /**
     * 화면 표시용 이메일 마스킹. 아이디 앞 2글자만 남기고 *** (예: buyer@palette.test → bu***@palette.test)
     * 아이디가 2글자 이하이면 첫 글자만 남긴다. null이나 @가 없는 값은 그대로(null) 돌려준다
     */
    public static String maskEmail(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        if (at <= 0) return email;
        String id = email.substring(0, at);
        String keep = id.length() <= 2 ? id.substring(0, 1) : id.substring(0, 2);
        return keep + "***" + email.substring(at);
    }
}
