package com.backend.boundedContext.coupon.in;

import com.backend.boundedContext.coupon.app.CouponFacade;
import com.backend.boundedContext.coupon.app.dto.MyCouponUsageDto;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 쿠폰 사용 내역. 경로는 /users/me 아래지만 쿠폰 데이터가 market-service에 있어서 여기서 받는다
 * (nginx에서 이 경로만 market으로 보낸다)
 */
@Tag(name = "내 쿠폰", description = "내 쿠폰 사용 내역 (구매자)")
@RestController
@RequestMapping("/api/v1/users/me/coupon-usages")
@RequiredArgsConstructor
public class ApiV1MyCouponUsageController {

    private final CouponFacade couponFacade;

    @Operation(
            summary = "내 쿠폰 사용 내역",
            description = "쿠폰명, 유형, 주문, 할인액, 상태(RESERVED 예약, USED 사용, RELEASED 해제)를 최신순으로 줍니다. "
                    + "page(0부터), size(기본 20, 최대 100)")
    @GetMapping
    public RsData<PageResponse<MyCouponUsageDto>> getMyCouponUsages(
            @AuthenticationPrincipal AuthMember authMember,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "쿠폰 사용 내역을 조회했습니다.",
                couponFacade.getMyCouponUsages(authMember.memberId(), pageable));
    }
}
