package com.backend.boundedContext.delivery.domain;

import static com.backend.shared.delivery.dto.DeliveryStatus.READY;
import static com.backend.shared.delivery.dto.DeliveryStatus.SHIPPED;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.hibernate.annotations.BatchSize;

import com.backend.global.exception.DomainException;
import com.backend.global.jpa.entity.BaseIdAndTime;
import com.backend.shared.delivery.dto.DeliveryDto;
import com.backend.shared.delivery.dto.DeliveryStatus;
import com.backend.shared.order.dto.PaidOrderItemDto;
import com.backend.shared.order.dto.ShippingAddressDto;
import com.backend.shared.order.event.OrderPaidEvent;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 배송. 플랫폼 통합 출고라 주문서 1개당 1건이다
 * 결제 완료(OrderPaidEvent)로 READY가 되고, 물류 담당자가 송장을 등록하면 SHIPPED, 배송 완료를 확인하면 DELIVERED.
 */
@Entity
@Table(name = "delivery")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Delivery extends BaseIdAndTime {

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false, length = 40)
    private String orderNumber;

    @Column(nullable = false)
    private Long buyerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryStatus status;

    @Column(nullable = false)
    private LocalDateTime paidAt;

    @Column(nullable = false, length = 50)
    private String receiverName;

    @Column(nullable = false, length = 20)
    private String receiverPhone;

    @Column(nullable = false, length = 10)
    private String zipCode;

    @Column(nullable = false, length = 200)
    private String address;

    @Column(length = 200)
    private String addressDetail;

    @Column(length = 100)
    private String requestMessage;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Courier courier;

    // 정규화(공백·하이픈 제거)한 값. TrackingNumbers.normalize
    @Column(length = 30)
    private String trackingNumber;

    private LocalDateTime shippedAt;

    private LocalDateTime deliveredAt;

    // Delivery Tracker 웹훅 등록 만료 시각. null이면 아직 등록하지 않음
    private LocalDateTime webhookExpiresAt;

    // 콜백을 받아 조회를 기다리는 시각
    private LocalDateTime trackingCheckRequestedAt;

    @Column(length = 40)
    private String lastTrackingStatus;

    @OneToMany(mappedBy = "delivery", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id asc")
    @BatchSize(size = 100)
    private List<DeliveryItem> items = new ArrayList<>();

    /**
     * 결제 완료 이벤트로 출고 대기(READY) 배송을 만든다.
     *
     * @throws IllegalArgumentException 배송지 없음, 항목 없음, 수량 0 이하, 필수 값 없음.
     *         다시 받아도 결과가 같은 메시지라 컨슈머가 재시도 없이 DLT로 보낸다(KafkaConsumerConfig)
     */
    public static Delivery create(OrderPaidEvent event) {
        validate(event);
        ShippingAddressDto shipping = event.shippingAddress();

        Delivery delivery = new Delivery();
        delivery.orderId = event.orderId();
        delivery.orderNumber = event.orderNumber();
        delivery.buyerId = event.buyerId();
        delivery.status = READY;
        delivery.paidAt = event.paidAt();
        delivery.receiverName = shipping.receiverName();
        delivery.receiverPhone = shipping.receiverPhone();
        delivery.zipCode = shipping.zipCode();
        delivery.address = shipping.address();
        delivery.addressDetail = shipping.addressDetail();
        delivery.requestMessage = shipping.requestMessage();
        for (PaidOrderItemDto item : event.items()) {
            delivery.items.add(DeliveryItem.create(delivery, item));
        }
        return delivery;
    }

    // 송장 등록: READY → SHIPPED
    public void ship(Courier courier, String trackingNumber, LocalDateTime now) {
        if (status != READY) {
            throw new DomainException(DeliveryErrorCode.DELIVERY_STATE_INVALID);
        }
        this.courier = courier;
        this.trackingNumber = trackingNumber;
        this.status = SHIPPED;
        this.shippedAt = now;
        this.webhookExpiresAt = null;
        this.trackingCheckRequestedAt = null;
    }

    /**
     * 송장 수정: SHIPPED에서만. 같은 값이면 아무것도 바꾸지 않는다.
     * 바뀌면 새 송장으로 웹훅을 다시 등록하도록 웹훅·조회 상태를 비운다.
     *
     * @return 바뀌었으면 true
     */
    public boolean changeTracking(Courier courier, String trackingNumber) {
        if (status != SHIPPED) {
            throw new DomainException(DeliveryErrorCode.DELIVERY_STATE_INVALID);
        }
        if (this.courier == courier && Objects.equals(this.trackingNumber, trackingNumber)) {
            return false;
        }
        this.courier = courier;
        this.trackingNumber = trackingNumber;
        this.webhookExpiresAt = null;
        this.trackingCheckRequestedAt = null;
        this.lastTrackingStatus = null;
        return true;
    }

    // 주문 상세에 붙이는 배송 정보
    public DeliveryDto toDto() {
        return new DeliveryDto(getId(), status, carrierName(), trackingNumber, shippedAt, deliveredAt);
    }

    // 택배사 표시 이름. 송장 등록 전이면 null
    public String carrierName() {
        return courier == null ? null : courier.getDisplayName();
    }

    private static void validate(OrderPaidEvent event) {
        require(event.orderId() != null, "orderId");
        require(event.orderNumber() != null, "orderNumber");
        require(event.buyerId() != null, "buyerId");
        require(event.paidAt() != null, "paidAt");

        ShippingAddressDto shipping = event.shippingAddress();
        require(shipping != null, "shippingAddress");
        require(shipping.receiverName() != null && shipping.receiverPhone() != null
                && shipping.zipCode() != null && shipping.address() != null, "shippingAddress 필수 값");

        require(event.items() != null && !event.items().isEmpty(), "items");
        for (PaidOrderItemDto item : event.items()) {
            require(item != null, "items 원소");
            require(item.quantity() > 0, "items.quantity(1 이상)");
            require(item.orderItemId() != null && item.productId() != null && item.optionId() != null
                    && item.sellerId() != null && item.sellerName() != null
                    && item.productName() != null && item.optionName() != null, "items 필수 값");
        }
    }

    // 배송지 값은 메시지에 넣지 않는다(DLT 로그에 남는다). 어떤 필드가 문제인지만 알린다
    private static void require(boolean condition, String field) {
        if (!condition) {
            throw new IllegalArgumentException("배송을 만들 수 없는 결제 완료 이벤트입니다: " + field);
        }
    }
}
