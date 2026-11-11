package com.backend.boundedContext.coupon.in;

import com.backend.boundedContext.coupon.app.CouponFacade;
import com.backend.boundedContext.coupon.app.dto.PartnershipDto;
import com.backend.boundedContext.coupon.app.dto.PickedProductDto;
import com.backend.boundedContext.coupon.domain.PartnershipStatus;
import com.backend.boundedContext.coupon.in.dto.PartnershipIdResponse;
import com.backend.boundedContext.coupon.in.dto.PartnershipRequestRequest;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "판매자 협약", description = "인플루언서 협약 요청, 조회, 종료 (승인된 판매자)")
@RestController
@RequestMapping("/api/v1/seller/partnerships")
@RequiredArgsConstructor
public class ApiV1SellerPartnershipController {

    private final CouponFacade couponFacade;

    @Operation(summary = "협약 요청", description = "승인된 인플루언서에게 협약을 요청합니다 → REQUESTED")
    @ApiResponse(responseCode = "403", description = "403-SELLER_NOT_APPROVED")
    @ApiResponse(responseCode = "404", description = "404-INFLUENCER_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "409-INFLUENCER_STATE_INVALID / 409-PARTNERSHIP_ALREADY_EXISTS")
    @PostMapping
    public RsData<PartnershipIdResponse> requestPartnership(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody PartnershipRequestRequest request
    ) {
        Long partnershipId = couponFacade.requestPartnership(authMember.memberId(), request.influencerId(), request.message());
        return RsData.of("200-1", "협약을 요청했습니다.", new PartnershipIdResponse(partnershipId));
    }

    @Operation(summary = "내 협약 목록", description = "최신 요청순. page(0부터), size(기본 20, 최대 100)")
    @GetMapping
    public RsData<PageResponse<PartnershipDto>> getPartnerships(
            @AuthenticationPrincipal AuthMember authMember,
            @Parameter(description = "REQUESTED, ACTIVE, REJECTED, ENDED (없으면 전체)")
            @RequestParam(required = false) PartnershipStatus status,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "협약 목록을 조회했습니다.",
                couponFacade.getSellerPartnerships(authMember.memberId(), status, pageable));
    }

    @Operation(summary = "협약 인플루언서가 pick한 내 상품", description = "상품 쿠폰 발행 대상. 내 판매 중 상품만, 최신 상품순")
    @ApiResponse(responseCode = "403", description = "403-FORBIDDEN 내 협약이 아님")
    @ApiResponse(responseCode = "404", description = "404-PARTNERSHIP_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "409-PARTNERSHIP_NOT_ACTIVE")
    @GetMapping("/{partnershipId}/picked-products")
    public RsData<List<PickedProductDto>> getPickedProducts(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long partnershipId
    ) {
        return RsData.of("200-1", "pick 상품 목록을 조회했습니다.",
                couponFacade.getPickedProducts(authMember.memberId(), partnershipId));
    }

    @Operation(summary = "협약 종료", description = "진행 중인 협약을 종료합니다 → ENDED. 그 협약의 상품 쿠폰은 모두 중지됩니다.")
    @ApiResponse(responseCode = "403", description = "403-FORBIDDEN 내 협약이 아님")
    @ApiResponse(responseCode = "404", description = "404-PARTNERSHIP_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "409-PARTNERSHIP_STATE_INVALID")
    @PostMapping("/{partnershipId}/end")
    public RsData<Void> endPartnership(@AuthenticationPrincipal AuthMember authMember, @PathVariable Long partnershipId) {
        couponFacade.endPartnershipBySeller(authMember.memberId(), partnershipId);
        return RsData.of("200-1", "협약을 종료했습니다.");
    }
}
