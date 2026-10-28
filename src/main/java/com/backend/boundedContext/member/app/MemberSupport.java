package com.backend.boundedContext.member.app;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;

import lombok.RequiredArgsConstructor;

// 여러 UseCase가 함께 쓰는 회원 조회
@Component
@RequiredArgsConstructor
public class MemberSupport {

    private final MemberRepository memberRepository;

    // 회원 조회 (없으면 404 MEMBER_NOT_FOUND)
    public Member getMember(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(() -> new DomainException(MemberErrorCode.MEMBER_NOT_FOUND));
    }

    // 여러 회원 조회 (없는 ID는 빠짐, 빈 목록이면 쿼리를 날리지 않음)
    public List<Member> getMembers(Collection<Long> memberIds) {
        if (memberIds.isEmpty()) return List.of();
        return memberRepository.findAllById(memberIds);
    }

    // 이메일로 회원 조회 (정규화한 이메일과 정확히 일치). 회원 컨텍스트 밖(관리자 결제·충전 검색 등)에서 온 값도 여기서 정규화함
    public Optional<Member> findMemberByEmail(String email) {
        return memberRepository.findByEmail(Member.normalizeEmail(email));
    }

    // 로그인 회원 조회 (토큰은 유효하지만 회원이 없거나 탈퇴했으면 로그인 상태가 아니므로 401 UNAUTHORIZED)
    public Member getLoginMember(Long memberId) {
        return memberRepository.findById(memberId)
                .filter(Member::isActive)
                .orElseThrow(() -> new DomainException(GlobalErrorCode.UNAUTHORIZED));
    }

    public long countMembers() {
        return memberRepository.count();
    }
}
