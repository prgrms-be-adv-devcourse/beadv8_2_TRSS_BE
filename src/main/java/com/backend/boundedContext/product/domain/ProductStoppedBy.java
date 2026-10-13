package com.backend.boundedContext.product.domain;

/**
 * 판매 중지 주체 (product.stopped_by).
 */
public enum ProductStoppedBy {
    ADMIN,              // 관리자 즉시 중지
    SELLER_REQUEST,     // 판매자 중지 요청을 관리자가 승인
    SELLER_WITHDRAWN    // 판매자 철회·제재로 일괄 중지
}
