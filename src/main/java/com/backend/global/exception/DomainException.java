package com.backend.global.exception;

import lombok.Getter;

@Getter
public class DomainException extends RuntimeException {
    private final String resultCode;
    private final String msg;
    private final Object data;   // 실패 응답 RsData.data에 실을 추가 정보(없으면 null)

    public DomainException(ErrorCode errorCode) {
        this(errorCode.resultCode(), errorCode.msg(), null, null);
    }

    public DomainException(ErrorCode errorCode, Object data) {
        this(errorCode.resultCode(), errorCode.msg(), data, null);
    }

    public DomainException(ErrorCode errorCode, Throwable cause) {
        this(errorCode.resultCode(), errorCode.msg(), null, cause);
    }

    private DomainException(String resultCode, String msg, Object data, Throwable cause) {
        super(resultCode + " : " + msg, cause);
        this.resultCode = resultCode;
        this.msg = msg;
        this.data = data;
    }

    public int statusCode() {
        return Integer.parseInt(resultCode.split("-", 2)[0]);
    }
}
