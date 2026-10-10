package com.backend.shared.member.out;

import com.backend.shared.member.dto.MemberDto;

/**
 * 회원 컨텍스트가 다른 컨텍스트에 제공하는 내부 API
 */
public interface MemberApi {

    // 회원 조회
    MemberDto getMember(Long memberId);
}
