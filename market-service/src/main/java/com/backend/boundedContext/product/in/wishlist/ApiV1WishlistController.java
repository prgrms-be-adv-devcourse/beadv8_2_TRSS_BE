package com.backend.boundedContext.product.in.wishlist;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.boundedContext.product.app.dto.WishlistItemDto;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "찜", description = "구매자의 상품 찜 추가·해제와 내 찜 목록")
@RestController
@RequestMapping("/api/v1/wishlist")
@RequiredArgsConstructor
public class ApiV1WishlistController {
    private final ProductFacade productFacade;

    @Operation(
            summary = "찜 목록",
            description = "최근에 찜한 순서입니다. 판매 중지·삭제된 상품도 목록에 남고 available이 false입니다.")
    @GetMapping
    public RsData<PageResponse<WishlistItemDto>> getWishlist(
            @AuthenticationPrincipal AuthMember authMember,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "찜 목록을 조회했습니다.",
                productFacade.getWishlist(authMember.memberId(), pageable));
    }

    @Operation(summary = "찜 추가", description = "이미 찜한 상품이면 그대로 성공합니다. 품절 상품도 찜할 수 있습니다.")
    @ApiResponse(responseCode = "200", description = "200-1 찜 추가")
    @ApiResponse(responseCode = "404", description = "404-PRODUCT_NOT_FOUND 없거나 구매자에게 보이지 않는 상품(판매 중지·삭제·카테고리 사용 중지)")
    @PutMapping("/{productId}")
    public RsData<Void> addWishlist(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long productId
    ) {
        productFacade.addWishlist(authMember.memberId(), productId);
        return RsData.of("200-1", "상품을 찜했습니다.");
    }

    @Operation(summary = "찜 해제", description = "찜하지 않은 상품이면 그대로 성공합니다. 판매 중지·삭제된 상품도 해제할 수 있습니다.")
    @DeleteMapping("/{productId}")
    public RsData<Void> removeWishlist(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long productId
    ) {
        productFacade.removeWishlist(authMember.memberId(), productId);
        return RsData.of("200-1", "찜을 해제했습니다.");
    }
}
