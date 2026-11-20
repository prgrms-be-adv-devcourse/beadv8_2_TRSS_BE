package com.backend.boundedContext.payment.out.pg;

import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;

/**
 * 조회했는데 PG에 결제가 없음 → 승인 요청이 PG에 닿지 않았거나 승인되지 않음 (돈이 빠지지 않음)
 * 잡지 않으면 502 EXTERNAL_SERVICE_ERROR
 */
public class PgPaymentNotFoundException extends DomainException {

    public PgPaymentNotFoundException(Throwable cause) {
        super(GlobalErrorCode.EXTERNAL_SERVICE_ERROR, cause);
    }
}
