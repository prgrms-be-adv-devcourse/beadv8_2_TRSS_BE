package com.backend.boundedContext.member.app.dto;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberSocialAccount;
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
        InfluencerStatus influencerStatus,   // 인플루언서 신청 상태. 신청한 적이 없으면 null (마이페이지 메뉴 노출에 쓴다)
        String profileImageUrl   // 카카오 프로필 이미지 주소. 카카오 미연결·미동의·기본 이미지면 null
) {
    public static MyInfoDto of(Member member, MemberSocialAccount kakaoAccount, InfluencerStatus influencerStatus) {
        return new MyInfoDto(member.getId(), member.getEmail(), member.getName(), member.getRole(), member.getCreatedAt(),
                kakaoAccount != null, influencerStatus, kakaoAccount != null ? kakaoAccount.getProfileImageUrl() : null);
    }
}
