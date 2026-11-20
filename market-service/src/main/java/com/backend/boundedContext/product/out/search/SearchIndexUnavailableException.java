package com.backend.boundedContext.product.out.search;

/**
 * 검색 색인을 지금 쓸 수 없음 (ES 접속·응답 실패, alias 없음, 매핑 버전이 낮은 이전 인덱스의 거부 등).
 * 시간이 지나면(ES 복구, 복구 재색인) 같은 요청이 성공하므로, 검색 색인 컨슈머는 이 예외면 성공할 때까지 다시 처리한다.
 * 그 밖의 예외(특정 상품 데이터 문제 등)는 다시 해도 같으므로 그 상품만 건너뛴다.
 */
public class SearchIndexUnavailableException extends IllegalStateException {

    public SearchIndexUnavailableException(String message) {
        super(message);
    }

    public SearchIndexUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
