package com.backend.boundedContext.payment.out.pg;

import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;

import lombok.Getter;

/**
 * PG가 결제를 거절함 (카드 거절·한도 초과 등 확정된 실패).
 * 잡지 않으면 502 EXTERNAL_SERVICE_ERROR
 */
@Getter
public class PgDeclinedException extends DomainException {

    private final String code;        // PG 오류 코드
    private final String pgMessage;   // PG 오류 메시지 (사용자에게 보여 줄 수 있는 문구, 없으면 null)

    public PgDeclinedException(String code, String pgMessage, Throwable cause) {
        super(GlobalErrorCode.EXTERNAL_SERVICE_ERROR, cause);
        this.code = code;
        this.pgMessage = pgMessage;
    }
}
