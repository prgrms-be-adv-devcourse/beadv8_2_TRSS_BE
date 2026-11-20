package com.backend.boundedContext.coupon.in;

import com.backend.boundedContext.coupon.app.CouponFacade;
import com.backend.boundedContext.coupon.app.dto.ProductCouponDto;
import com.backend.boundedContext.coupon.domain.CouponDisplayStatus;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인플루언서 쿠폰", description = "나에게 발행된 쿠폰 조회 (승인된 인플루언서)")
@RestController
@RequestMapping("/api/v1/influencer/coupons")
@RequiredArgsConstructor
public class ApiV1InfluencerCouponController {

    private final CouponFacade couponFacade;

    @Operation(
            summary = "나에게 발행된 쿠폰 목록",
            description = "상호명, 쿠폰 코드, 할인 내용, 대상 상품, 유효 기간, 사용 수. 최신 발행순. page(0부터), size(기본 20, 최대 100)")
    @ApiResponse(responseCode = "403", description = "403-INFLUENCER_NOT_APPROVED")
    @GetMapping
    public RsData<PageResponse<ProductCouponDto>> getCoupons(
            @AuthenticationPrincipal AuthMember authMember,
            @Parameter(description = "SCHEDULED, ACTIVE, ENDED, STOPPED. 없으면 전체")
            @RequestParam(required = false) CouponDisplayStatus status,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "쿠폰 목록을 조회했습니다.",
                couponFacade.getInfluencerCoupons(authMember.memberId(), status, pageable));
    }
}
