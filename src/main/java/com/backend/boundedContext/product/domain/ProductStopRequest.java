package com.backend.boundedContext.product.domain;

import com.backend.global.exception.DomainException;
import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import static jakarta.persistence.FetchType.LAZY;

/**
 * 판매자의 판매 중지 요청. 관리자가 검토해 승인(상품 STOPPED)하거나 반려한다.
 * 상품마다 심사 중(PENDING)인 요청은 하나만 둘 수 있다 (DB 부분 유니크 인덱스).
 */
@Entity
@Table(name = "product_stop_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductStopRequest extends BaseIdAndTime {

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private Long sellerId;

    @Column(nullable = false, length = 300)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStopRequestStatus status;

    @Column(length = 300)
    private String rejectReason;

    /** 관리자가 승인·반려했거나 요청이 자동으로 닫힌 시각 */
    private LocalDateTime reviewedAt;

    /** 판매 중(품절 포함)인 상품에만 요청할 수 있다. 판매 중지·삭제된 상품이면 409. */
    public static ProductStopRequest create(Product product, String reason) {
        product.checkEditable();

        ProductStopRequest request = new ProductStopRequest();
        request.product = product;
        request.sellerId = product.getSellerId();
        request.reason = reason.strip();
        request.status = ProductStopRequestStatus.PENDING;
        return request;
    }

    /** 심사 중에 상품이 삭제되거나 다른 이유로 중지되어 요청을 닫는다. 심사 중인 요청에만 쓴다. */
    public void cancel() {
        checkPending();
        status = ProductStopRequestStatus.CANCELED;
        reviewedAt = LocalDateTime.now();
    }

    /** 관리자가 승인한다. 상품은 판매자가 적은 사유로 판매 중지(SELLER_REQUEST)된다. 이미 처리된 요청이면 409. */
    public void approve() {
        checkPending();
        product.stopBySellerRequest(reason);
        status = ProductStopRequestStatus.APPROVED;
        reviewedAt = LocalDateTime.now();
    }

    /** 관리자가 반려 사유를 적고 반려한다. 상품은 그대로 판매 중이다. 이미 처리된 요청이면 409. */
    public void reject(String rejectReason) {
        checkPending();
        status = ProductStopRequestStatus.REJECTED;
        this.rejectReason = rejectReason.strip();
        reviewedAt = LocalDateTime.now();
    }

    private void checkPending() {
        if (status != ProductStopRequestStatus.PENDING) {
            throw new DomainException(ProductErrorCode.STOP_REQUEST_NOT_PENDING);
        }
    }
}
