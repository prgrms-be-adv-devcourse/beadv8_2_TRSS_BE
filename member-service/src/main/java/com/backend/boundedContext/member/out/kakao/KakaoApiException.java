package com.backend.boundedContext.member.out.kakao;

import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;

import lombok.Getter;

/**
 * 카카오 API 호출이 우리 쪽 설정 문제나 카카오 장애로 실패함 (키 오류, 5xx, 응답 없음).
 * 사용자가 다시 시도해도 해결되지 않을 수 있어 502 EXTERNAL_SERVICE_ERROR로 응답함
 * (인가 코드가 만료·재사용된 경우는 400 KAKAO_AUTH_FAILED로 따로 구분)
 */
@Getter
public class KakaoApiException extends DomainException {

    private final int httpStatus;   // 카카오 응답 상태 (응답을 받지 못했으면 0)
    private final String code;      // 카카오 오류 코드 (예: invalid_client, KOE010)

    public KakaoApiException(int httpStatus, String code, Throwable cause) {
        super(GlobalErrorCode.EXTERNAL_SERVICE_ERROR, cause);
        this.httpStatus = httpStatus;
        this.code = code;
    }
}
