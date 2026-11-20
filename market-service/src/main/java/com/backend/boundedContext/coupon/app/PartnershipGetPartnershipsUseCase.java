package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.app.dto.PartnershipDto;
import com.backend.boundedContext.coupon.domain.Partnership;
import com.backend.boundedContext.coupon.domain.PartnershipStatus;
import com.backend.boundedContext.coupon.out.CouponInfluencerPort;
import com.backend.boundedContext.coupon.out.CouponSellerPort;
import com.backend.boundedContext.coupon.out.PartnershipRepository;
import com.backend.boundedContext.coupon.out.PartnershipSpecifications;
import com.backend.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 협약 목록 (판매자 내 협약, 인플루언서 받은 협약, 관리자 전체 협약). 최신 요청순이고, 보내지 않은 조건은 적용하지 않는다
 */
@Component
@RequiredArgsConstructor
public class PartnershipGetPartnershipsUseCase {

    private final CouponSupport couponSupport;
    private final CouponSellerPort couponSellerPort;
    private final CouponInfluencerPort couponInfluencerPort;
    private final PartnershipRepository partnershipRepository;

    public PageResponse<PartnershipDto> getForSeller(Long memberId, PartnershipStatus status, Pageable pageable) {
        Long sellerId = couponSellerPort.getApprovedSellerId(memberId);
        return search(sellerId, null, status, pageable);
    }

    public PageResponse<PartnershipDto> getForInfluencer(Long memberId, PartnershipStatus status, Pageable pageable) {
        Long influencerId = couponInfluencerPort.getApprovedInfluencerIdByMemberId(memberId);
        return search(null, influencerId, status, pageable);
    }

    public PageResponse<PartnershipDto> getForAdmin(Long sellerId, Long influencerId, PartnershipStatus status, Pageable pageable) {
        return search(sellerId, influencerId, status, pageable);
    }

    private PageResponse<PartnershipDto> search(Long sellerId, Long influencerId, PartnershipStatus status, Pageable pageable) {
        // 요청의 sort는 무시하고 최신 요청순으로만 준다
        Pageable latestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "id"));

        Page<Partnership> partnerships = partnershipRepository.findAll(
                PartnershipSpecifications.sellerEquals(sellerId)
                        .and(PartnershipSpecifications.influencerEquals(influencerId))
                        .and(PartnershipSpecifications.statusEquals(status)),
                latestFirst);

        Map<Long, PartnershipDto> dtos = couponSupport.toPartnershipDtos(partnerships.getContent()).stream()
                .collect(Collectors.toMap(PartnershipDto::partnershipId, Function.identity()));
        return PageResponse.from(partnerships, partnership -> dtos.get(partnership.getId()));
    }
}
