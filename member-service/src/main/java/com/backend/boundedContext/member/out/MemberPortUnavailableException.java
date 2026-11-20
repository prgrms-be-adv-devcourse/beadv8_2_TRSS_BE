package com.backend.boundedContext.member.out;

/**
 * 회원 Port가 다른 모듈을 부르지 못했을 때 던짐 (시간 초과·연결 실패·상대 서버 5xx)
 * 상대가 RsData 오류로 응답한 경우(예: 404-WALLET_NOT_FOUND)는 같은 resultCode의 DomainException으로 던지고 이 예외는 쓰지 않음
 * 같은 앱 안 구현(*ApiAdapter)은 던지지 않고, HTTP 구현(*HttpAdapter)이 던짐
 */
public class MemberPortUnavailableException extends RuntimeException {

    public MemberPortUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
