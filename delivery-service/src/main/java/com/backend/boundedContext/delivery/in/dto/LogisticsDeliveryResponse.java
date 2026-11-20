package com.backend.boundedContext.delivery.in.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.backend.boundedContext.delivery.domain.Courier;
import com.backend.boundedContext.delivery.domain.Delivery;
import com.backend.boundedContext.delivery.domain.DeliveryItem;
import com.backend.shared.delivery.dto.DeliveryStatus;

// 물류 담당자·관리자용 배송 건 (출고 목록·상세·송장 등록·수동 완료 응답). 배송지를 포함한다
public record LogisticsDeliveryResponse(
        Long deliveryId,
        Long orderId,
        String orderNumber,
        DeliveryStatus status,
        LocalDateTime paidAt,
        String receiverName,
        String receiverPhone,
        String zipCode,
        String address,
        String addressDetail,
        String requestMessage,
        Courier courier,
        String carrierName,
        String trackingNumber,
        LocalDateTime shippedAt,
        LocalDateTime deliveredAt,
        String lastTrackingStatus,
        String manualCompletionReason,   // 관리자 수동 완료 사유. 택배사 조회로 완료됐거나 아직 완료 전이면 null
        LocalDateTime manuallyCompletedAt,
        List<Item> items
) {

    // thumbnailUrl: 응답할 때 market에서 조회한 상품의 현재 대표 이미지. 이미지가 없거나 조회에 실패하면 null
    public record Item(Long orderItemId, Long sellerId, String sellerName, String productName,
                       String optionName, int quantity, Long productId, String thumbnailUrl) {

        static Item from(DeliveryItem item, Map<Long, String> thumbnails) {
            return new Item(item.getOrderItemId(), item.getSellerId(), item.getSellerName(),
                    item.getProductName(), item.getOptionName(), item.getQuantity(),
                    item.getProductId(), thumbnails.get(item.getOptionId()));
        }
    }

    // 이미지 없이 (thumbnailUrl: null)
    public static LogisticsDeliveryResponse from(Delivery delivery) {
        return from(delivery, Map.of());
    }

    // thumbnails: 옵션 ID → 대표 이미지 URL
    public static LogisticsDeliveryResponse from(Delivery delivery, Map<Long, String> thumbnails) {
        return new LogisticsDeliveryResponse(
                delivery.getId(),
                delivery.getOrderId(),
                delivery.getOrderNumber(),
                delivery.getStatus(),
                delivery.getPaidAt(),
                delivery.getReceiverName(),
                delivery.getReceiverPhone(),
                delivery.getZipCode(),
                delivery.getAddress(),
                delivery.getAddressDetail(),
                delivery.getRequestMessage(),
                delivery.getCourier(),
                delivery.carrierName(),
                delivery.getTrackingNumber(),
                delivery.getShippedAt(),
                delivery.getDeliveredAt(),
                delivery.getLastTrackingStatus(),
                delivery.getManualCompletionReason(),
                delivery.getManuallyCompletedAt(),
                delivery.getItems().stream().map(item -> Item.from(item, thumbnails)).toList()
        );
    }
}
