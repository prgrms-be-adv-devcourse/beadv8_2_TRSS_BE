package com.backend.boundedContext.payment.out.pg;

import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;

import lombok.Getter;

/**
 * PG 연동 설정 오류 (시크릿 키 누락·키 세트 불일치 등). 사용자 잘못이 아니라 우리 설정 문제
 * 잡지 않으면 502 EXTERNAL_SERVICE_ERROR
 */
@Getter
public class PgConfigurationException extends DomainException {

    private final String code;   // PG 오류 코드

    public PgConfigurationException(String code, Throwable cause) {
        super(GlobalErrorCode.EXTERNAL_SERVICE_ERROR, cause);
        this.code = code;
    }
}
