package com.backend.boundedContext.order.in;

import com.backend.boundedContext.order.app.OrderFacade;
import com.backend.boundedContext.order.in.dto.CartItemAddRequest;
import com.backend.boundedContext.order.in.dto.CartItemAddResponse;
import com.backend.boundedContext.order.in.dto.CartItemUpdateRequest;
import com.backend.boundedContext.order.in.dto.CartResponse;
import com.backend.boundedContext.order.in.dto.CartSelectionRequest;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 장바구니 API
 */
@Tag(name = "장바구니", description = "장바구니 조회·담기·변경·삭제")
@RestController
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
public class ApiV1CartController {

    private final OrderFacade orderFacade;

    @Operation(summary = "장바구니 조회", description = "장바구니 항목별 주문 가능 여부, 구매 불가 사유, 예상 결제 금액을 조회한다.")
    @GetMapping
    public RsData<CartResponse> getCart(@AuthenticationPrincipal AuthMember authMember) {
        return RsData.of("200-1", "장바구니를 조회했습니다.", orderFacade.getCart(authMember.memberId()));
    }

    @Operation(summary = "장바구니 담기", description = "옵션 단위로 담고, 같은 옵션은 수량을 합산한다.")
    @PostMapping("/items")
    public RsData<CartItemAddResponse> addItem(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody CartItemAddRequest request
    ) {
        CartItemAddResponse response = orderFacade.addCartItem(authMember.memberId(), request.optionId(), request.quantity());
        return RsData.of("200-1", "장바구니에 담았습니다.", response);
    }

    @Operation(summary = "장바구니 항목 수량·선택 여부 변경", description = "장바구니 항목, 수량, 선택 여부를 변경한다.")
    @PatchMapping("/items/{itemId}")
    public RsData<Void> updateItem(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long itemId,
            @Valid @RequestBody CartItemUpdateRequest request
    ) {
        orderFacade.updateCartItem(authMember.memberId(), itemId, request.quantity(), request.selected());
        return RsData.of("200-1", "장바구니 항목을 변경했습니다.");
    }

    @Operation(summary = "장바구니 전체 선택·해제")
    @PatchMapping("/items/selection")
    public RsData<Void> selectAll(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody CartSelectionRequest request
    ) {
        orderFacade.selectAllCartItems(authMember.memberId(), request.selected());
        return RsData.of("200-1", request.selected() ? "전체 선택했습니다." : "전체 선택을 해제했습니다.");
    }

    @Operation(summary = "장바구니 항목 삭제", description = "장바구니 항목을 삭제할 수 있습니다.")
    @DeleteMapping("/items")
    public RsData<Void> deleteItems(
            @AuthenticationPrincipal AuthMember authMember,
            @RequestParam @NotEmpty List<Long> itemIds
    ) {
        orderFacade.deleteCartItems(authMember.memberId(), itemIds);
        return RsData.of("200-1", "장바구니 항목을 삭제했습니다.");
    }
}
