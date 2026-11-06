package com.backend.boundedContext.member.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.app.dto.AdminSellerDetailDto;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MemberGetSellerDetailUseCase {

    private final SellerSupport sellerSupport;

    // 관리자 판매자 상세 (판매자 정보 + 상태 이력 전체). 없으면 404
    public AdminSellerDetailDto getSellerDetail(Long sellerId) {
        return sellerSupport.getSellerDetail(sellerSupport.getSeller(sellerId));
    }
}
