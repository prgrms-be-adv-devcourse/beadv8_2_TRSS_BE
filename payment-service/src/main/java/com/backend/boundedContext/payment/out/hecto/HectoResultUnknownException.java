package com.backend.boundedContext.payment.out.hecto;

import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;

/**
 * 헥토에서 처리됐는지 알 수 없음: 응답을 받지 못함(시간 초과·연결 끊김), HTTP 오류 응답, 해석할 수 없는 응답, 해시가 맞지 않는 응답
 * 실패로 단정하지 않고 충전 건을 PROCESSING·CANCELING으로 둬서, 스케줄러가 조회로 최종 결과를 반영하게 함
 */
public class HectoResultUnknownException extends DomainException {

    public HectoResultUnknownException(Throwable cause) {
        super(GlobalErrorCode.EXTERNAL_SERVICE_ERROR, cause);
    }

    public HectoResultUnknownException(String message) {
        this(new IllegalStateException(message));
    }
}
