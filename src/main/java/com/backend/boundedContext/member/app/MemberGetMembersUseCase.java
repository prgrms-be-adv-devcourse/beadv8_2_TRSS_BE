package com.backend.boundedContext.member.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.app.dto.AdminMemberDto;
import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.member.out.MemberSpecifications;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.MemberStatus;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MemberGetMembersUseCase {

    // 가입일 최신순 (같은 시각이면 ID 역순)
    private static final Sort LATEST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final MemberRepository memberRepository;

    // 관리자 회원 목록 (이메일 부분 검색, 상태·역할 필터, 탈퇴 회원 포함)
    public PageResponse<AdminMemberDto> getMembers(String email, MemberStatus status, MemberRole role, Pageable pageable) {
        Specification<Member> spec = Specification.allOf(
                MemberSpecifications.emailContains(email),
                MemberSpecifications.statusEquals(status),
                MemberSpecifications.roleEquals(role)
        );
        Pageable latestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST);

        Page<Member> members = memberRepository.findAll(spec, latestFirst);
        return PageResponse.from(members, AdminMemberDto::from);
    }
}
