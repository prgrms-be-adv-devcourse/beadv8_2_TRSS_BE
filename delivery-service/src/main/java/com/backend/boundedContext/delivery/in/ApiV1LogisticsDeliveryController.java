package com.backend.boundedContext.delivery.in;

import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backend.boundedContext.delivery.app.DeliveryFacade;
import com.backend.boundedContext.delivery.in.dto.DeliveryShipRequest;
import com.backend.boundedContext.delivery.in.dto.DeliveryTrackingChangeRequest;
import com.backend.boundedContext.delivery.in.dto.LogisticsDeliveryResponse;
import com.backend.global.page.PageResponse;
import com.backend.global.rsData.RsData;
import com.backend.shared.delivery.dto.DeliveryStatus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 물류 담당자 배송 API. 권한(LOGISTICS만)은 common SecurityConfig가 /api/v1/logistics/**에 건다.
 */
@Tag(name = "물류 배송", description = "출고 대기 목록·상세, 송장 등록·수정 (물류 담당자 전용)")
@RestController
@RequestMapping("/api/v1/logistics/deliveries")
@RequiredArgsConstructor
public class ApiV1LogisticsDeliveryController {

    private final DeliveryFacade deliveryFacade;

    @Operation(summary = "배송 목록", description = "상태별 배송 목록")
    @GetMapping
    public RsData<PageResponse<LogisticsDeliveryResponse>> getDeliveries(
            @RequestParam(defaultValue = "READY") DeliveryStatus status,
            @ParameterObject Pageable pageable
    ) {
        return RsData.of("200-1", "배송 목록을 조회했습니다.", deliveryFacade.getDeliveries(status, pageable));
    }

    @Operation(summary = "배송 상세")
    @GetMapping("/{deliveryId}")
    public RsData<LogisticsDeliveryResponse> getDelivery(@PathVariable Long deliveryId) {
        return RsData.of("200-1", "배송 정보를 조회했습니다.", deliveryFacade.getDelivery(deliveryId));
    }

    @Operation(summary = "송장 등록", description = "출고 대기(READY) 배송에 택배사·송장번호를 등록하고 배송 중(SHIPPED)으로 바꿉니다.")
    @PostMapping("/{deliveryId}/ship")
    public RsData<LogisticsDeliveryResponse> ship(@PathVariable Long deliveryId,
                                                  @Valid @RequestBody DeliveryShipRequest request) {
        return RsData.of("200-1", "송장을 등록했습니다.",
                deliveryFacade.ship(deliveryId, request.courier(), request.trackingNumber()));
    }

    @Operation(summary = "송장 수정", description = "배송 중(SHIPPED) 배송의 택배사·송장번호를 바로잡습니다. 같은 값이면 바꾸지 않습니다.")
    @PatchMapping("/{deliveryId}/tracking")
    public RsData<LogisticsDeliveryResponse> changeTracking(@PathVariable Long deliveryId,
                                                            @Valid @RequestBody DeliveryTrackingChangeRequest request) {
        return RsData.of("200-1", "송장을 수정했습니다.",
                deliveryFacade.changeTracking(deliveryId, request.courier(), request.trackingNumber()));
    }
}
