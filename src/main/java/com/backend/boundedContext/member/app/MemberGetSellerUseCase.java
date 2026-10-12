package com.backend.boundedContext.member.app;

import com.backend.boundedContext.member.domain.MemberErrorCode;
import com.backend.boundedContext.member.domain.Seller;
import com.backend.boundedContext.member.out.SellerRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

// 다른 컨텍스트에 판매자 정보를 제공하는 조회 (MemberApi용). 계좌 정보는 넘기지 않는다
@Component
@RequiredArgsConstructor
public class MemberGetSellerUseCase {
    private final SellerRepository sellerRepository;

    // 회원의 판매자 (상태 무관), 신청한 적이 없으면 empty
    public Optional<SellerDto> findSellerByMemberId(Long memberId) {
        return sellerRepository.findByMemberId(memberId).map(Seller::toDto);
    }

    // 판매자 1명 조회, 없으면 404
    public SellerDto getSeller(Long sellerId) {
        return sellerRepository.findById(sellerId)
                .map(Seller::toDto)
                .orElseThrow(() -> new DomainException(MemberErrorCode.SELLER_NOT_FOUND));
    }

    // 여러 판매자 조회, 없는 ID는 결과에서 빠짐
    public List<SellerDto> getSellers(Collection<Long> sellerIds) {
        return sellerRepository.findAllById(sellerIds).stream()
                .map(Seller::toDto)
                .toList();
    }
}
