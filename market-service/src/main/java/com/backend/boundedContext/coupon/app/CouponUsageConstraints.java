package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.domain.CouponErrorCode;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 쿠폰 사용 기록 저장이 부분 유니크 인덱스에 걸렸을 때 어떤 오류로 돌려줄지 정한다
 * - uk_coupon_usage_order_type: 같은 주문에 같은 종류의 쿠폰이 이미 있음 → 409-COUPON_ORDER_LIMIT_EXCEEDED
 * - 그 밖(uk_coupon_usage_member_active): 같은 회원이 같은 쿠폰을 이미 예약·사용함 → 409-COUPON_ALREADY_USED
 */
final class CouponUsageConstraints {

    static final String ORDER_TYPE_UNIQUE = "uk_coupon_usage_order_type";

    private CouponUsageConstraints() {
    }

    static CouponErrorCode errorCodeOf(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains(ORDER_TYPE_UNIQUE)) return CouponErrorCode.COUPON_ORDER_LIMIT_EXCEEDED;
        }
        return CouponErrorCode.COUPON_ALREADY_USED;
    }
}
