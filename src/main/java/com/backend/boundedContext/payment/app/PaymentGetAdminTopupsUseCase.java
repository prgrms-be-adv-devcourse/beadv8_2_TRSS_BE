package com.backend.boundedContext.payment.app;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.app.dto.AdminTopupDto;
import com.backend.boundedContext.payment.domain.Topup;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.dto.MemberDto;
import com.backend.shared.member.out.MemberApi;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentGetAdminTopupsUseCase {

    // 충전 준비 최신순 (같은 시각이면 ID 역순). 요청의 sort는 무시
    private static final Sort LATEST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final TopupAdminSearchResolver searchResolver;
    private final TopupRepository topupRepository;
    private final MemberApi memberApi;

    /**
     * 관리자 결제 조회 충전 탭
     * 회원 이메일은 페이지에 나온 충전 건의 회원만 모아 한 번에 조회 (행마다 조회하지 않음)
     */
    public PageResponse<AdminTopupDto> getTopups(String keyword, LocalDate from, LocalDate to, Pageable pageable) {
        AdminTopupSearch search = searchResolver.resolve(keyword, from, to);
        Pageable latestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST);

        Page<Topup> topups = search.noResult() ? Page.empty(latestFirst) : topupRepository.findAll(search.topupSpec(), latestFirst);

        Map<Long, String> emailByMemberId = getEmails(topups.getContent());
        return PageResponse.from(topups, topup -> AdminTopupDto.of(topup, emailByMemberId.get(topup.getMemberId())));
    }

    private Map<Long, String> getEmails(List<Topup> topups) {
        List<Long> memberIds = topups.stream().map(Topup::getMemberId).distinct().toList();
        if (memberIds.isEmpty()) return Map.of();

        return memberApi.getMembers(memberIds).stream()
                .collect(Collectors.toMap(MemberDto::id, MemberDto::email));
    }
}
