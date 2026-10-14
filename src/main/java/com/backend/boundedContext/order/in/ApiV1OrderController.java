package com.backend.boundedContext.order.in;

import com.backend.boundedContext.order.app.OrderFacade;
import com.backend.boundedContext.order.in.dto.OrderCreateRequest;
import com.backend.boundedContext.order.in.dto.OrderCreateResponse;
import com.backend.boundedContext.order.in.dto.OrderSummaryResponse;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import com.backend.shared.order.dto.OrderStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * 주문 API
 */
@Tag(name = "주문", description = "주문 생성·조회·취소")
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class ApiV1OrderController {

    private final OrderFacade orderFacade;

    @Operation(summary = "주문 생성", description = "주문을 생성하고 재고를 차감한다")
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

    @Operation(summary = "내 주문 목록", description = "최신주문일을 기준으로 내 주문 목록을 조회합니다.")
    @GetMapping
    public RsData<PageResponse<OrderSummaryResponse>> getOrders(
            @AuthenticationPrincipal AuthMember authMember,
            @RequestParam(required = false) OrderStatus status,
            @Parameter(description = "조회 시작일 (yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일 (yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "주문 목록을 조회했습니다.",
                orderFacade.getOrders(authMember.memberId(), status, from, to, pageable));
    }
}
