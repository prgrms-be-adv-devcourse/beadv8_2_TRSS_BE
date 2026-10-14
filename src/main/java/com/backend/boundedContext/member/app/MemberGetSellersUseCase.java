package com.backend.boundedContext.member.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.app.dto.AdminSellerDto;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.dto.SellerStatus;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MemberGetSellersUseCase {

    private final SellerRepository sellerRepository;

    // 관리자 판매자 목록 (PENDING 먼저, 그다음 최근 신청순. 상태 필터)
    public PageResponse<AdminSellerDto> getSellers(SellerStatus status, Pageable pageable) {
        Pageable pageOnly = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());

        Page<Seller> sellers = sellerRepository.findAllForAdmin(status, pageOnly);
        return PageResponse.from(sellers, AdminSellerDto::from);
    }
}
