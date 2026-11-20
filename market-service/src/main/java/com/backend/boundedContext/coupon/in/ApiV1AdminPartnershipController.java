package com.backend.boundedContext.coupon.in;

import com.backend.boundedContext.coupon.app.CouponFacade;
import com.backend.boundedContext.coupon.app.dto.PartnershipDto;
import com.backend.boundedContext.coupon.domain.PartnershipStatus;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 협약", description = "전체 협약 조회 (ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/partnerships")
@RequiredArgsConstructor
public class ApiV1AdminPartnershipController {

    private final CouponFacade couponFacade;

    @Operation(summary = "전체 협약 목록", description = "최신 요청순. 보내지 않은 조건은 적용하지 않습니다. page(0부터), size(기본 20, 최대 100)")
    @GetMapping
    public RsData<PageResponse<PartnershipDto>> getPartnerships(
            @RequestParam(required = false) Long sellerId,
            @RequestParam(required = false) Long influencerId,
            @RequestParam(required = false) PartnershipStatus status,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "협약 목록을 조회했습니다.",
                couponFacade.getPartnershipsForAdmin(sellerId, influencerId, status, pageable));
    }
}
