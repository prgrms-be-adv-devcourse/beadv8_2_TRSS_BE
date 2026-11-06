package com.backend.boundedContext.member.app;

import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.app.dto.AdminSellerCountsDto;
import com.backend.boundedContext.member.app.dto.AdminSellerDto;
import com.backend.boundedContext.member.app.dto.AdminSellerListDto;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.boundedContext.member.out.SellerStatusCountRow;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.dto.SellerStatus;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MemberGetSellersUseCase {

    private final SellerRepository sellerRepository;

    // 관리자 판매자 목록 + 탭별 건수
    public AdminSellerListDto getSellers(SellerStatus status, Pageable pageable) {
        Pageable pageOnly = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());

        Page<Seller> sellers = sellerRepository.findAllForAdmin(status, pageOnly);
        return new AdminSellerListDto(PageResponse.from(sellers, AdminSellerDto::from), getCounts());
    }

    private AdminSellerCountsDto getCounts() {
        Map<SellerStatus, Long> counts = sellerRepository.countGroupByStatus().stream()
                .collect(
                        Collectors.toMap(
                                SellerStatusCountRow::status,
                                SellerStatusCountRow::count
                        )
                );
        return AdminSellerCountsDto.from(counts);
    }
}
