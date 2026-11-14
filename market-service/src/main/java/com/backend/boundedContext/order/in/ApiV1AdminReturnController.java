package com.backend.boundedContext.order.in;

import com.backend.boundedContext.order.app.OrderFacade;
import com.backend.boundedContext.order.domain.ReturnFilter;
import com.backend.boundedContext.order.in.dto.AdminReturnSummaryResponse;
import com.backend.boundedContext.order.in.dto.ReturnApproveRequest;
import com.backend.boundedContext.order.in.dto.ReturnRejectRequest;
import com.backend.boundedContext.order.in.dto.ReturnResultResponse;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 반품 API (/api/v1/admin/** → ADMIN)
 */
@Tag(name = "관리자 반품", description = "반품 요청 목록·승인·거절 (ADMIN). 환불은 결제 서비스가 반품 승인 이벤트를 받아 처리한다")
@RestController
@RequestMapping("/api/v1/admin/returns")
@RequiredArgsConstructor
public class ApiV1AdminReturnController {

    private final OrderFacade orderFacade;

    @Operation(
            summary = "반품 목록",
            description = "반품을 요청한 주문 목록입니다. filter 기본값은 REQUESTED(처리 대기)이고 먼저 요청한 순으로 줍니다. "
                    + "나머지 필터는 최근 요청 순입니다(sort 값은 무시). page(0부터), size(기본 20, 최대 100). 배송지·연락처는 내려주지 않습니다.")
    @ApiResponse(responseCode = "200", description = "200-1 조회 완료")
    @ApiResponse(responseCode = "400", description = "400-INVALID_REQUEST 없는 filter 값")
    @ApiResponse(responseCode = "401", description = "401 로그인 필요")
    @ApiResponse(responseCode = "403", description = "403 ADMIN이 아님")
    @GetMapping
    public RsData<PageResponse<AdminReturnSummaryResponse>> getReturns(
            @Parameter(description = "REQUESTED(처리 대기) · RETURNED(승인) · REJECTED(거절) · ALL(전체)")
            @RequestParam(defaultValue = "REQUESTED") ReturnFilter filter,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "반품 목록을 조회했습니다.", orderFacade.getAdminReturns(filter, pageable));
    }

    @Operation(
            summary = "반품 승인",
            description = "주문을 RETURNED로 바꾸고, restoreStock=true인 항목만 수량 전체를 재고로 되돌리고, 반품 승인 이벤트를 발행합니다(한 트랜잭션). "
                    + "주문의 모든 항목을 한 번씩 보내야 합니다. 환불 금액은 배송비를 포함한 결제 금액 전액이고 환불은 결제 서비스가 처리합니다. "
                    + "사용한 쿠폰은 돌려주지 않습니다. 삭제된 상품·옵션에 restoreStock=true를 보내면 409이고 아무것도 바뀌지 않으므로 그 항목을 false로 다시 승인하세요.")
    @ApiResponse(responseCode = "200", description = "200-1 승인 완료(status: RETURNED, confirmType: null)")
    @ApiResponse(responseCode = "400", description = "400-VALIDATION_FAILED items 비어 있음·값 누락 / 400-INVALID_REQUEST 본문 형식 오류 / "
            + "400-RETURN_ITEMS_MISMATCH 항목 누락·중복·다른 주문의 항목 / 400-STOCK_LIMIT_EXCEEDED 복원하면 재고 상한 초과")
    @ApiResponse(responseCode = "404", description = "404-ORDER_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "409-ORDER_STATE_INVALID 반품 요청 중(RETURN_REQUESTED)이 아님(이미 승인·거절됨 포함) / "
            + "409-PRODUCT_DELETED 삭제된 상품 복원 / 409-OPTION_DELETED 삭제된 옵션 복원")
    @PostMapping("/{orderId}/approve")
    public RsData<ReturnResultResponse> approve(@PathVariable Long orderId,
                                                @Valid @RequestBody ReturnApproveRequest request) {
        return RsData.of("200-1", "반품을 승인했습니다.", orderFacade.approveReturn(orderId, request.toDecisions()));
    }

    @Operation(
            summary = "반품 거절",
            description = "주문을 결제 완료(PAID)로 되돌립니다. 거절 사유는 필수이고 구매자 주문 상세에 보입니다. "
                    + "배송 완료(배송지 없는 주문은 결제 완료) 후 7일이 이미 지났으면 바로 구매 확정(CONFIRMED, confirmType RETURN_REJECTED)합니다. "
                    + "7일 전이면 PAID로 두고 원래 기준일에 자동 구매 확정됩니다. 거절된 주문은 다시 반품을 요청할 수 없습니다.")
    @ApiResponse(responseCode = "200", description = "200-1 거절 완료(status: PAID, confirmType: null 또는 status: CONFIRMED, confirmType: RETURN_REJECTED)")
    @ApiResponse(responseCode = "400", description = "400-VALIDATION_FAILED 사유 없음·공백·300자 초과 / 400-INVALID_REQUEST 본문 형식 오류")
    @ApiResponse(responseCode = "404", description = "404-ORDER_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "409-ORDER_STATE_INVALID 반품 요청 중(RETURN_REQUESTED)이 아님(이미 승인·거절됨 포함)")
    @PostMapping("/{orderId}/reject")
    public RsData<ReturnResultResponse> reject(@PathVariable Long orderId,
                                               @Valid @RequestBody ReturnRejectRequest request) {
        return RsData.of("200-1", "반품을 거절했습니다.", orderFacade.rejectReturn(orderId, request.reason()));
    }
}
