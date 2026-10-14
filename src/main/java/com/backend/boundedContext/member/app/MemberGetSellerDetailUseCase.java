package com.backend.boundedContext.member.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.app.dto.AdminSellerDetailDto;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.boundedContext.member.out.SellerStatusHistoryRepository;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class MemberGetSellerDetailUseCase {

    private final SellerRepository sellerRepository;
    private final SellerStatusHistoryRepository sellerStatusHistoryRepository;

    // 관리자 판매자 상세 (판매자 정보 + 상태 이력 전체). 없으면 404
    public AdminSellerDetailDto getSellerDetail(Long sellerId) {
        Seller seller = sellerRepository.findById(sellerId)
                .orElseThrow(() -> new DomainException(MemberErrorCode.SELLER_NOT_FOUND));

        return AdminSellerDetailDto.of(seller, sellerStatusHistoryRepository.findAllBySellerIdOrderByIdAsc(sellerId));
    }
}
