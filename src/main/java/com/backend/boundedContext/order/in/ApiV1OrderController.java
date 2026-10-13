package com.backend.boundedContext.order.in;

import com.backend.boundedContext.order.app.OrderFacade;
import com.backend.boundedContext.order.in.dto.OrderCreateRequest;
import com.backend.boundedContext.order.in.dto.OrderCreateResponse;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 주문 API
 */
@Tag(name = "주문", description = "주문 생성·조회·취소")
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class ApiV1OrderController {

    private final OrderFacade orderFacade;

    @Operation(summary = "주문 생성", description = "장바구니 항목으로 결제 대기 주문을 만들고 재고를 차감한다")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RsData<OrderCreateResponse> createOrder(
            @AuthenticationPrincipal AuthMember authMember,
            @Valid @RequestBody OrderCreateRequest request
    ) {
        OrderCreateResponse response = orderFacade.createOrder(
                authMember.memberId(), request.cartItemIds(), request.expectedTotalPrice());
        return RsData.of("201-1", "주문을 생성했습니다.", response);
    }
}