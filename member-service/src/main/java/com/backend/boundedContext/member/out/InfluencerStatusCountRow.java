package com.backend.boundedContext.member.out;

import com.backend.shared.member.dto.InfluencerStatus;

// 관리자 인플루언서 목록의 탭별 건수용. JPQL count()는 Long을 돌려준다
public record InfluencerStatusCountRow(InfluencerStatus status, Long count) {
}
