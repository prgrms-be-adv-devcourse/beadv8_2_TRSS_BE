package com.backend.boundedContext.payment.out.pg;

import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;

/**
 * PG 결과를 알 수 없음 (응답 없음·응답 해석 실패·5xx·이미 처리된 결제).
 * PG에서는 이미 승인됐을 수 있으므로 실패로 단정하지 않고 충전 건을 PROCESSING으로 둠
 * 잡지 않으면 502 EXTERNAL_SERVICE_ERROR
 */
public class PgResultUnknownException extends DomainException {

    public PgResultUnknownException(Throwable cause) {
        super(GlobalErrorCode.EXTERNAL_SERVICE_ERROR, cause);
    }
}
