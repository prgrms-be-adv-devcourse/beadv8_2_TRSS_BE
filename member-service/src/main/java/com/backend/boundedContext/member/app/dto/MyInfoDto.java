package com.backend.boundedContext.member.app.dto;

import com.backend.boundedContext.member.domain.Member;
import com.backend.shared.member.dto.InfluencerStatus;
import com.backend.shared.member.dto.MemberRole;

import java.time.LocalDateTime;

// 내 정보 조회 결과
public record MyInfoDto(
        Long memberId,
        String email,
        String name,
        MemberRole role,
        LocalDateTime createdAt,
        boolean kakaoLinked,   // 카카오 계정 연결 여부 (연결하면 카카오로 로그인할 수 있음)
        InfluencerStatus influencerStatus   // 인플루언서 신청 상태. 신청한 적이 없으면 null (마이페이지 메뉴 노출에 쓴다)
) {
    public static MyInfoDto of(Member member, boolean kakaoLinked, InfluencerStatus influencerStatus) {
        return new MyInfoDto(member.getId(), member.getEmail(), member.getName(), member.getRole(), member.getCreatedAt(), kakaoLinked, influencerStatus);
    }
}
