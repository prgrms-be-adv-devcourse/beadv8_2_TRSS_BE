package com.backend.boundedContext.payment.app.dto;

import java.time.LocalDateTime;

import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.domain.TopupStatus;

import io.swagger.v3.oas.annotations.media.Schema;

// 관리자 결제 조회의 충전 탭 항목 (충전 건 1건)
@Schema(description = "관리자 결제 조회의 충전 탭 항목. 성공·실패를 포함한 충전 건 1건")
public record AdminTopupDto(
        @Schema(description = "충전 건 ID", example = "14")
        Long topupId,

        @Schema(description = "충전번호 (토스 주문번호)", example = "TOPUP-88e21df8-bddb-4ff6-9ea1-dd5e1cc3cc04")
        String orderId,

        @Schema(description = "충전한 회원 ID", example = "3")
        Long memberId,

        @Schema(description = "충전한 회원 이메일. 회원을 찾을 수 없으면 null", example = "buyer@palette.test")
        String memberEmail,

        @Schema(description = "충전 금액", example = "20000")
        long amount,

        @Schema(description = "결제에 쓰이고 남은 금액. 승인 완료 건만 의미가 있음", example = "20000")
        long remainingAmount,

        @Schema(description = """
                충전 상태
                - READY: 충전 준비, 결제창 진행 전 (15분 지나면 만료)
                - PROCESSING: 토스 승인 중 또는 결과 확인 대기
                - DONE: 충전 완료
                - FAILED_USER_CANCEL: 사용자가 결제창을 닫음
                - FAILED_PG_DECLINED: 카드사·토스 승인 거절
                - FAILED_AMOUNT_MISMATCH: 준비 금액과 승인 요청 금액이 다름
                - FAILED_EXPIRED: 결제창 진행 시간 초과
                - FAILED_SYSTEM_ERROR: 그 외 오류
                - CANCELED: 미사용 충전 취소 (현재 미사용)""", example = "DONE")
        TopupStatus status,

        @Schema(description = "실패 사유. 실패한 충전 건만 값이 있음")
        String failReason,

        @Schema(description = "토스 승인 시각. 충전 완료 건만 값이 있음", example = "2026-10-13T20:47:13")
        LocalDateTime approvedAt,

        @Schema(description = "충전 준비 시각 (화면의 충전일)", example = "2026-10-13T20:46:30")
        LocalDateTime createdAt
) {
    public static AdminTopupDto of(Topup topup, String memberEmail) {
        return new AdminTopupDto(topup.getId(), topup.getTossOrderId(), topup.getMemberId(), memberEmail,
                topup.getAmount(), topup.getRemainingAmount(), topup.getStatus(), topup.getFailReason(),
                topup.getApprovedAt(), topup.getCreatedAt());
    }
}
