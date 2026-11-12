package com.backend.boundedContext.order.domain;

/**
 * 반품 사유 코드
 */
public enum ReturnReasonCode {
    CHANGE_OF_MIND,     // 단순 변심
    DEFECTIVE,          // 불량·파손
    OTHER               // 기타 (상세 필수)
}
