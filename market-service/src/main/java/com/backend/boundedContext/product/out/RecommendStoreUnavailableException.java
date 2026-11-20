package com.backend.boundedContext.product.out;

/** 추천 저장소(Redis)가 응답하지 않는다. 저장한 결과가 "없는" 것과 구분해, 요청마다 다시 계산하지 않게 한다 */
public class RecommendStoreUnavailableException extends RuntimeException {

    public RecommendStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
