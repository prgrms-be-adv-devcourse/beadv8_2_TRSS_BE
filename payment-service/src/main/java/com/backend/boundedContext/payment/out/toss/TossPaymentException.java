package com.backend.boundedContext.payment.out.toss;

import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;

import lombok.Getter;

/**
 * 토스가 오류로 응답함 (결과가 확정된 실패).
 * 잡지 않으면 502 EXTERNAL_SERVICE_ERROR로 응답함. 카드 거절 등은 호출한 쪽에서 code로 구분
 */
@Getter
public class TossPaymentException extends DomainException {

    private final int httpStatus;
    private final String code;          // 토스 오류 코드
    private final String tossMessage;   // 토스 오류 메시지 (사용자에게 보여 줄 수 있는 문구)

    public TossPaymentException(int httpStatus, String code, String tossMessage, Throwable cause) {
        super(GlobalErrorCode.EXTERNAL_SERVICE_ERROR, cause);
        this.httpStatus = httpStatus;
        this.code = code;
        this.tossMessage = tossMessage;
    }
}
