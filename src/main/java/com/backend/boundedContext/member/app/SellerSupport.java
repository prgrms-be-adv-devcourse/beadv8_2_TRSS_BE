package com.backend.boundedContext.member.app;

import org.springframework.stereotype.Component;

import com.backend.boundedContext.member.app.dto.AdminSellerDetailDto;
import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.domain.SellerStatusHistory;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.boundedContext.member.out.SellerStatusHistoryRepository;
import com.backend.global.exception.DomainException;

import lombok.RequiredArgsConstructor;

// 관리자 판매자 UseCase들이 함께 쓰는 판매자 조회·이력 저장
@Component
@RequiredArgsConstructor
public class SellerSupport {

    private final SellerRepository sellerRepository;
    private final SellerStatusHistoryRepository sellerStatusHistoryRepository;

    // 판매자 조회 (없으면 404 SELLER_NOT_FOUND)
    public Seller getSeller(Long sellerId) {
        return sellerRepository.findById(sellerId)
                .orElseThrow(() -> new DomainException(MemberErrorCode.SELLER_NOT_FOUND));
    }

    // 상태 변경 이력을 저장하고, 바뀐 판매자를 관리자 상세 형태로 돌려준다
    // seller는 조회한 엔티티라 커밋 때 변경 감지로 저장된다
    public AdminSellerDetailDto saveHistory(Seller seller, SellerStatusHistory history) {
        sellerStatusHistoryRepository.save(history);
        return getSellerDetail(seller);
    }

    // 판매자 정보 + 상태 이력 전체 (오래된 순)
    public AdminSellerDetailDto getSellerDetail(Seller seller) {
        return AdminSellerDetailDto.of(seller, sellerStatusHistoryRepository.findAllBySellerIdOrderByIdAsc(seller.getId()));
    }
}
