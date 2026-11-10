package com.backend.boundedContext.coupon.in;

import com.backend.boundedContext.coupon.app.CouponFacade;
import com.backend.boundedContext.coupon.app.dto.PartnershipDto;
import com.backend.boundedContext.coupon.domain.PartnershipStatus;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인플루언서 협약", description = "받은 협약 조회, 수락, 거절, 종료 (승인된 인플루언서)")
@RestController
@RequestMapping("/api/v1/influencer/partnerships")
@RequiredArgsConstructor
public class ApiV1InfluencerPartnershipController {

    private final CouponFacade couponFacade;

    @Operation(summary = "받은 협약 목록", description = "상호명, 요청 메시지 포함. 최신 요청순. page(0부터), size(기본 20, 최대 100)")
    @ApiResponse(responseCode = "403", description = "403-INFLUENCER_NOT_APPROVED")
    @GetMapping
    public RsData<PageResponse<PartnershipDto>> getPartnerships(
            @AuthenticationPrincipal AuthMember authMember,
            @Parameter(description = "REQUESTED, ACTIVE, REJECTED, ENDED (없으면 전체)")
            @RequestParam(required = false) PartnershipStatus status,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "협약 목록을 조회했습니다.",
                couponFacade.getInfluencerPartnerships(authMember.memberId(), status, pageable));
    }

    @Operation(summary = "협약 수락", description = "요청 중인 협약을 수락합니다 → ACTIVE")
    @ApiResponse(responseCode = "403", description = "403-INFLUENCER_NOT_APPROVED / 403-FORBIDDEN 내 협약이 아님")
    @ApiResponse(responseCode = "404", description = "404-PARTNERSHIP_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "409-PARTNERSHIP_STATE_INVALID")
    @PostMapping("/{partnershipId}/accept")
    public RsData<Void> accept(@AuthenticationPrincipal AuthMember authMember, @PathVariable Long partnershipId) {
        couponFacade.acceptPartnership(authMember.memberId(), partnershipId);
        return RsData.of("200-1", "협약을 수락했습니다.");
    }

    @Operation(summary = "협약 거절", description = "요청 중인 협약을 거절합니다 → REJECTED")
    @ApiResponse(responseCode = "403", description = "403-INFLUENCER_NOT_APPROVED / 403-FORBIDDEN 내 협약이 아님")
    @ApiResponse(responseCode = "404", description = "404-PARTNERSHIP_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "409-PARTNERSHIP_STATE_INVALID")
    @PostMapping("/{partnershipId}/reject")
    public RsData<Void> reject(@AuthenticationPrincipal AuthMember authMember, @PathVariable Long partnershipId) {
        couponFacade.rejectPartnership(authMember.memberId(), partnershipId);
        return RsData.of("200-1", "협약을 거절했습니다.");
    }

    @Operation(summary = "협약 종료", description = "진행 중인 협약을 종료합니다 → ENDED. 그 협약의 상품 쿠폰은 모두 중지됩니다.")
    @ApiResponse(responseCode = "403", description = "403-INFLUENCER_NOT_APPROVED / 403-FORBIDDEN 내 협약이 아님")
    @ApiResponse(responseCode = "404", description = "404-PARTNERSHIP_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "409-PARTNERSHIP_STATE_INVALID")
    @PostMapping("/{partnershipId}/end")
    public RsData<Void> end(@AuthenticationPrincipal AuthMember authMember, @PathVariable Long partnershipId) {
        couponFacade.endPartnershipByInfluencer(authMember.memberId(), partnershipId);
        return RsData.of("200-1", "협약을 종료했습니다.");
    }
}
