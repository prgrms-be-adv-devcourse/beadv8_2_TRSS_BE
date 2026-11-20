package com.backend.boundedContext.order.in;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.global.rsData.RsData;
import com.backend.shared.order.dto.OrderSummaryDto;
import com.backend.shared.order.out.OrderApi;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;

/**
 * 다른 서비스가 호출하는 주문 내부 API. 규칙은 {@link OrderApi}를 따른다.
 * 서비스끼리만 부르는 API라 Swagger에서 숨긴다.
 * TODO 서비스 간 인증 방식이 정해지면 보안 설정 추가
 */
@Hidden
@RestController
@RequestMapping("/internal/v1/orders")
@RequiredArgsConstructor
public class InternalV1OrderController {

    private final OrderApi orderApi;

    @Operation(summary = "구매자 진행 중 주문 존재 여부")
    @GetMapping("/active/exists")
    public RsData<Boolean> hasActiveOrders(@RequestParam Long buyerId) {
        return RsData.of("200-1", "진행 중인 주문 여부를 조회했습니다.", orderApi.hasActiveOrders(buyerId));
    }

    @Operation(summary = "판매자 진행 중 판매 존재 여부")
    @GetMapping("/active-sales/exists")
    public RsData<Boolean> hasActiveSales(@RequestParam Long sellerId) {
        return RsData.of("200-1", "진행 중인 판매 여부를 조회했습니다.", orderApi.hasActiveSales(sellerId));
    }

    @Operation(summary = "구매자 최근 주문 목록")
    @GetMapping("/recent")
    public RsData<List<OrderSummaryDto>> getRecentOrders(@RequestParam Long buyerId, @RequestParam int limit) {
        return RsData.of("200-1", "최근 주문을 조회했습니다.", orderApi.getRecentOrders(buyerId, limit));
    }
}
