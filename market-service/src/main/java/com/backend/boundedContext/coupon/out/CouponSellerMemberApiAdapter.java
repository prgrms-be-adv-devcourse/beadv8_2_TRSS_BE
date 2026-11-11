package com.backend.boundedContext.coupon.out;

import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.out.MemberApi;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@link CouponSellerPort} 구현. member-service를 {@link MemberApi}로 부른다
 */
@Component
@RequiredArgsConstructor
public class CouponSellerMemberApiAdapter implements CouponSellerPort {

    private final MemberApi memberApi;

    @Override
    public Long getApprovedSellerId(Long memberId) {
        return memberApi.getApprovedSellerByMemberId(memberId).sellerId();
    }

    @Override
    public Map<Long, String> getBusinessNames(Collection<Long> sellerIds) {
        if (sellerIds.isEmpty()) return Map.of();

        return memberApi.getSellers(new HashSet<>(sellerIds)).stream()
                .collect(Collectors.toMap(SellerDto::sellerId, SellerDto::businessName, (first, second) -> first));
    }
}
