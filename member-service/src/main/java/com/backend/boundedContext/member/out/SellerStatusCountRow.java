package com.backend.boundedContext.member.out;

import com.backend.shared.member.dto.SellerStatus;

/**
 * 판매자를 상태별로 센 조회 결과 (관리자 판매자 목록의 탭별 건수용)
 * JPQL count()는 Long을 돌려주므로 필드도 Long
 */
public record SellerStatusCountRow(SellerStatus status, Long count) {
}