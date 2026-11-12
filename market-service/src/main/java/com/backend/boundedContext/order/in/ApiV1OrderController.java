package com.backend.boundedContext.order.in;

import com.backend.boundedContext.order.app.OrderFacade;
import com.backend.boundedContext.order.in.dto.*;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import com.backend.global.security.AuthMember;
import com.backend.shared.order.dto.OrderStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
import java.util.List;

/**
 * 주문 API
 */
@Tag(name = "주문", description = "주문 생성·조회·취소·구매확정")
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class ApiV1OrderController {

    private final OrderFacade orderFacade;

    @Operation(summary = "주문 생성", description = "주문을 생성하고 재고를 차감한다. 배송지는 선택 값이다(보내면 형식을 검증하고, 배송지가 있는 주문은 배송 완료 뒤에만 구매 확정된다). "
            + "쿠폰(상품 쿠폰 1개, 장바구니 쿠폰 1개까지)은 재고 차감과 같은 트랜잭션에서 예약한다. "
            + "결제 금액은 상품 금액 합계 - 상품 쿠폰 할인 - 장바구니 쿠폰 할인 + 배송비다. "
            + "배송비는 상품 쿠폰 할인 후 상품 금액 합계가 30,000원 이상이면 0원, 미만이면 2,500원이고 주문당 한 번이다(장바구니 쿠폰은 배송비에 영향 없음). "
            + "쿠폰은 서버 설정이 켜져 있을 때만 반영되고, 꺼져 있을 때 쿠폰을 보내면 409-ORDER_COUPON_NOT_AVAILABLE이다. "
            + "expectedTotalPrice는 배송비를 뺀 금액이다(쿠폰 없음: 상품 금액 합계, 쿠폰 있음: 쿠폰 할인 후 금액). "
            + "이 금액이 다르면 409-PRICE_CHANGED이고 data.currentAmount에 지금 계산한 배송비 제외 금액을 담는다.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RsData<OrderCreateResponse> createOrder(
            @AuthenticationPrincipal AuthMember authMember,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody OrderCreateRequest request
    ) {
        OrderCreateResponse response = orderFacade.createOrder(
                authMember.memberId(), idempotencyKey, request.cartItemIds(), request.expectedTotalPrice(),
                request.shippingAddress(), request.toCouponSelection());
        return RsData.of("201-1", "주문을 생성했습니다.", response);
    }

    @Operation(summary = "내 주문 목록", description = "최신주문일을 기준으로 내 주문 목록을 조회한다.")
    @GetMapping
    public RsData<PageResponse<OrderSummaryResponse>> getOrders(
            @AuthenticationPrincipal AuthMember authMember,
            @Parameter(description = "주문 상태")
            @RequestParam(required = false) List<OrderStatus> status,
            @Parameter(description = "조회 시작일 (yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "조회 종료일 (yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "주문 목록을 조회했습니다.",
                orderFacade.getOrders(authMember.memberId(), status, from, to, pageable));
    }

    @Operation(summary = "주문 상세", description = "주문 항목 스냅샷, 결제 남은 시간, 결제 내역, 배송지·배송 정보를 조회한다.")
    @GetMapping("/{orderId}")
    public RsData<OrderDetailResponse> getOrder(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long orderId
    ) {
        return RsData.of("200-1", "주문 상세를 조회했습니다.",
                orderFacade.getOrderDetail(authMember.memberId(), orderId));
    }

    @Operation(summary = "주문 결제", description = "결제 대기상태이고 만료 전인 주문만 예치금 결제가 가능하다. "
            + "결제 결과를 바로 알 수 없으면 200-2(status: PAYING, 결제 확인 중)로 응답하고, 서버가 결과를 확인해 PAID·PAYMENT_FAILED로 바꾼다. "
            + "프론트는 주문 상세를 다시 조회해 상태를 확인한다.")
    @ApiResponse(responseCode = "200", description = "200-1 결제 완료 / 200-2 결제 확인 중(status: PAYING, paidAt·paymentId 없음)")
    @ApiResponse(responseCode = "402", description = "402-INSUFFICIENT_BALANCE 잔액 부족(data: 부족액·추천 충전액·남은 시간)")
    @ApiResponse(responseCode = "404", description = "404-ORDER_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "409-ORDER_EXPIRED / 409-ORDER_STATE_INVALID / 409-PRODUCT_NOT_ON_SALE / "
            + "409-COUPON_NOT_ACTIVE / 409-PAYMENT_IN_PROGRESS 결제 확인 중인 주문")
    @PostMapping("/{orderId}/payment")
    public RsData<OrderPayResponse> payOrder(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long orderId
    ) {
        OrderPayResponse response = orderFacade.payOrder(authMember.memberId(), orderId);
        if (response.status() == OrderStatus.PAYING) {
            return RsData.of("200-2", "결제를 확인하고 있습니다. 잠시 후 주문 상태를 확인해 주세요.", response);
        }
        return RsData.of("200-1", "결제를 완료했습니다.", response);
    }

    @Operation(summary = "주문 취소", description = "결제 전(결제 대기·결제 실패) 주문만 전체 취소하고 재고를 복원한다.")
    @ApiResponse(responseCode = "200", description = "200-1 취소 완료")
    @ApiResponse(responseCode = "404", description = "404-ORDER_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "409-ORDER_NOT_CANCELABLE 결제 후이거나 이미 취소된 주문 / 409-PAYMENT_IN_PROGRESS 결제 확인 중인 주문")
    @PostMapping("/{orderId}/cancel")
    public RsData<OrderCancelResponse> cancelOrder(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long orderId
    ) {
        return RsData.of("200-1", "주문을 취소했습니다.",
                orderFacade.cancelOrder(authMember.memberId(), orderId));
    }

    @Operation(summary = "구매 확정", description = "결제 완료(PAID) 주문을 구매자가 직접 확정한다. 배송지가 있는 주문은 배송 완료 뒤에만 확정할 수 있다. 확정되면 정산 후보가 만들어진다.")
    @ApiResponse(responseCode = "200", description = "200-1 구매 확정 완료")
    @ApiResponse(responseCode = "404", description = "404-ORDER_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "409-ORDER_NOT_CONFIRMABLE 결제 전·반품 요청 중·이미 확정된 주문 / 409-ORDER_NOT_DELIVERED 배송지가 있는 주문의 배송 완료 전")
    @PostMapping("/{orderId}/confirm")
    public RsData<OrderConfirmResponse> confirmOrder(
            @AuthenticationPrincipal AuthMember authMember,
            @PathVariable Long orderId
    ) {
        return RsData.of("200-1", "구매를 확정했습니다.",
                orderFacade.confirmOrder(authMember.memberId(), orderId));
    }
}
