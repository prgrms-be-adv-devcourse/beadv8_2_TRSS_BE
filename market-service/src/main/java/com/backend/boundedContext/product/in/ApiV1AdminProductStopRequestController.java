package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.boundedContext.product.app.dto.AdminProductStopRequestDto;
import com.backend.boundedContext.product.domain.ProductStopRequestStatus;
import com.backend.boundedContext.product.in.dto.ProductReasonRequest;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

@Tag(name = "관리자 판매 중지 요청", description = "판매자의 판매 중지 요청 심사 (ADMIN)")
@RestController
@RequestMapping("/api/v1/admin/product-stop-requests")
@RequiredArgsConstructor
public class ApiV1AdminProductStopRequestController {
    private final ProductFacade productFacade;

    @Operation(
            summary = "판매 중지 요청 목록",
            description = "이 상태의 요청을 먼저 들어온 순으로 줍니다(sort 값은 무시). status 기본값은 PENDING(심사 중)입니다. "
                    + "page(0부터), size(기본 20, 최대 100)")
    @GetMapping
    public RsData<PageResponse<AdminProductStopRequestDto>> getStopRequests(
            @Parameter(description = "PENDING(심사 중) · APPROVED(승인) · REJECTED(반려) · CANCELED(상품 삭제·중지로 닫힘)")
            @RequestParam(defaultValue = "PENDING") ProductStopRequestStatus status,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "판매 중지 요청 목록을 조회했습니다.", productFacade.getStopRequestsForAdmin(status, pageable));
    }

    @Operation(
            summary = "판매 중지 요청 승인",
            description = "상품을 판매자가 적은 사유로 판매 중지(SELLER_REQUEST)하고, 결제 대기 중인 주문을 취소합니다. "
                    + "이미 처리된 요청은 409-STOP_REQUEST_NOT_PENDING입니다.")
    @PostMapping("/{requestId}/approve")
    public RsData<AdminProductStopRequestDto> approve(@PathVariable Long requestId) {
        return RsData.of("200-1", "판매 중지 요청을 승인했습니다.", productFacade.approveStopRequest(requestId));
    }

    @Operation(
            summary = "판매 중지 요청 반려",
            description = "반려 사유는 필수이고, 상품은 그대로 판매 중입니다. 이미 처리된 요청은 409-STOP_REQUEST_NOT_PENDING입니다.")
    @PostMapping("/{requestId}/reject")
    public RsData<AdminProductStopRequestDto> reject(@PathVariable Long requestId,
                                                     @Valid @RequestBody ProductReasonRequest request) {
        return RsData.of("200-1", "판매 중지 요청을 반려했습니다.", productFacade.rejectStopRequest(requestId, request.reason()));
    }
}
