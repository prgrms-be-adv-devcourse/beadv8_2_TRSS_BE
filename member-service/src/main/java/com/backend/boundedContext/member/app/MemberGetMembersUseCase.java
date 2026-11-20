package com.backend.boundedContext.member.app;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.app.dto.AdminMemberDto;
import com.backend.boundedContext.member.domain.Influencer;
import com.backend.boundedContext.member.domain.Member;
import com.backend.boundedContext.member.domain.MemberType;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.out.InfluencerRepository;
import com.backend.boundedContext.member.out.MemberRepository;
import com.backend.boundedContext.member.out.MemberSpecifications;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.dto.InfluencerStatus;
import com.backend.shared.member.dto.MemberStatus;
import com.backend.shared.member.dto.SellerStatus;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MemberGetMembersUseCase {

    // 가입일 최신순 (같은 시각이면 ID 역순)
    private static final Sort LATEST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final MemberRepository memberRepository;
    private final SellerRepository sellerRepository;
    private final InfluencerRepository influencerRepository;

    // 관리자 회원 목록 (이메일 부분 검색, 상태·회원 유형 필터, 탈퇴 회원 포함, 판매자·인플루언서 상태 함께)
    public PageResponse<AdminMemberDto> getMembers(String email, MemberStatus status, MemberType memberType, Pageable pageable) {
        Specification<Member> spec = Specification.allOf(
                MemberSpecifications.emailContains(email),
                MemberSpecifications.statusEquals(status),
                MemberSpecifications.memberTypeEquals(memberType)
        );
        Pageable latestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST);

        Page<Member> members = memberRepository.findAll(spec, latestFirst);
        List<Long> memberIds = members.getContent().stream().map(Member::getId).toList();
        Map<Long, SellerStatus> sellerStatuses = sellerStatusesOf(memberIds);
        Map<Long, InfluencerStatus> influencerStatuses = influencerStatusesOf(memberIds);
        return PageResponse.from(members, member -> AdminMemberDto.from(member, sellerStatuses.get(member.getId()), influencerStatuses.get(member.getId())));
    }

    // 이 페이지 회원들의 판매자 상태 (회원 ID → 상태). 판매자 조회는 한 번만 한다
    private Map<Long, SellerStatus> sellerStatusesOf(List<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return Map.of();
        }
        return sellerRepository.findAllByMemberIdIn(memberIds).stream()
                .collect(Collectors.toMap(seller -> seller.getMember().getId(), Seller::getStatus));
    }

    // 이 페이지 회원들의 인플루언서 상태 (회원 ID → 상태). 인플루언서 조회는 한 번만 함
    private Map<Long, InfluencerStatus> influencerStatusesOf(List<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return Map.of();
        }
        return influencerRepository.findAllByMemberIdIn(memberIds).stream()
                .collect(Collectors.toMap(influencer -> influencer.getMember().getId(), Influencer::getStatus));
    }
}
