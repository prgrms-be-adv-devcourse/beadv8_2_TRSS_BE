package com.backend.boundedContext.order.in;

import com.backend.boundedContext.order.app.OrderFacade;
import com.backend.boundedContext.order.in.dto.SellerOrderItemResponse;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import com.backend.shared.order.dto.OrderStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 판매자 주문 항목 API
 */
@Tag(name = "판매자 주문 항목", description = "본인 상품이 들어간 주문 항목 조회")
@RestController
@RequestMapping("/api/v1/seller/order-items")
@RequiredArgsConstructor
public class ApiV1SellerOrderItemController {

    private final OrderFacade orderFacade;

    @Operation(summary = "판매자 주문 항목 조회", description = "결제 완료 이후 주문 중 본인 상품 항목만 최신 주문순으로 조회합니다.")
    @GetMapping
    public RsData<PageResponse<SellerOrderItemResponse>> getSellerOrderItems(
            @AuthenticationPrincipal AuthMember authMember,
            @Parameter(description = "PAID · CONFIRMED · RETURN_REQUESTED · RETURNED (없으면 전체)")
            @RequestParam(required = false) OrderStatus status,
            @Parameter(description = "조회 시작일 (yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일 (yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "주문번호 또는 상품명 일부 (최대 100자)")
            @RequestParam(required = false) @Size(max = 100) String keyword,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "주문 항목을 조회했습니다.",
                orderFacade.getSellerOrderItems(authMember.memberId(), status, from, to, keyword, pageable));
    }
}
