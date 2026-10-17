package com.backend.boundedContext.payment.app;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.payment.app.dto.TopupDto;
import com.backend.boundedContext.payment.out.TopupRepository;
import com.backend.global.page.PageResponse;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class PaymentGetTopupsUseCase {

    // 충전 준비 최신순 (같은 시각이면 ID 역순). 요청의 sort는 무시하고 인덱스(member_id, created_at)에 맞는 정렬로 고정
    private static final Sort LATEST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final TopupRepository topupRepository;

    // 내 충전 목록
    public PageResponse<TopupDto> getTopups(Long memberId, Pageable pageable) {
        Pageable latestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST);
        return PageResponse.from(topupRepository.findByMemberId(memberId, latestFirst), TopupDto::from);
    }
}
