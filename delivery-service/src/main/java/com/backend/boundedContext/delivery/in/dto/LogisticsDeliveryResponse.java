package com.backend.boundedContext.delivery.in.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.backend.boundedContext.delivery.domain.Courier;
import com.backend.boundedContext.delivery.domain.Delivery;
import com.backend.boundedContext.delivery.domain.DeliveryItem;
import com.backend.shared.delivery.dto.DeliveryStatus;

// 물류 담당자용 배송 건 (출고 목록·상세·송장 등록 응답). 배송지를 포함한다
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
        List<Item> items
) {

    public record Item(Long orderItemId, Long sellerId, String sellerName, String productName,
                       String optionName, int quantity) {

        static Item from(DeliveryItem item) {
            return new Item(item.getOrderItemId(), item.getSellerId(), item.getSellerName(),
                    item.getProductName(), item.getOptionName(), item.getQuantity());
        }
    }

    public static LogisticsDeliveryResponse from(Delivery delivery) {
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
                delivery.getItems().stream().map(Item::from).toList()
        );
    }
}
