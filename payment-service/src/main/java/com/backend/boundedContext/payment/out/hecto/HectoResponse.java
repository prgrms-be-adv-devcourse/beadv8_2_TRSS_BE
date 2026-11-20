package com.backend.boundedContext.payment.out.hecto;

import java.time.LocalDateTime;

/**
 * 헥토 승인·취소·조회 응답 중 충전에 필요한 값. 금액은 복호화한 값이며, 응답에 없거나 실패 응답이면 null
 * - outStatCd: 거래 상태 (0021 성공, 0031 실패)
 * - outRsltCd: 결과 코드. 조회는 0000 승인 / 0001 취소 / 0009 결제 실패, 실패면 ST06(거래 없음) 등
 * - mchtTrdNo: 요청한 상점 주문번호. 조회는 조회 요청에 새로 만든 주문번호라 충전 주문번호와 다름
 * - trdNo: 헥토 거래번호. 승인 응답은 인증 거래번호와 같음
 * - transactedAt: 헥토가 처리한 일시 (한국 시간)
 * - amount: 승인 금액(승인), 원거래 금액(조회)
 * - canceledAmount: 이번 취소 금액(취소), 누적 취소 금액(조회)
 * - balanceAmount: 취소 후 남은 금액(취소만)
 */
public record HectoResponse(
        String outStatCd,
        String outRsltCd,
        String outRsltMsg,
        String mchtTrdNo,
        String trdNo,
        LocalDateTime transactedAt,
        Long amount,
        Long canceledAmount,
        Long balanceAmount
) {
    public static final String SUCCESS = "0021";

    public boolean isSuccess() {
        return SUCCESS.equals(outStatCd);
    }
}
