package com.backend.boundedContext.payment.out.toss;

import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;

/**
 * 토스 응답을 받지 못함 (응답 시간 초과·연결 끊김).
 * 토스에서는 이미 처리됐을 수 있으므로 실패로 단정하지 않고 충전 건을 PROCESSING으로 둠
 * 스케줄러가 주문번호 조회(getPaymentByOrderId)로 최종 결과를 반영
 */
public class TossTimeoutException extends DomainException {

    public TossTimeoutException(Throwable cause) {
        super(GlobalErrorCode.EXTERNAL_SERVICE_ERROR, cause);
    }
}
