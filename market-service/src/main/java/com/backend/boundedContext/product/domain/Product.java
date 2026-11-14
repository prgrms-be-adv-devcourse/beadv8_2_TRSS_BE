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
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static jakarta.persistence.FetchType.LAZY;

/**
 * 상품. 판매자가 등록한 "무엇을 얼마에 파는가"이며, 재고는 옵션({@link ProductOption})에 있다.
 * 판매자는 ID로만 참조한다 (회원 컨텍스트의 seller.id).
 */
@Entity
@Table(name = "product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseIdAndTime {

    @Column(nullable = false)
    private Long sellerId;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private long price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductSaleStatus status;

    @Column(length = 300)
    private String stoppedReason;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ProductStoppedBy stoppedBy;

    private LocalDateTime stoppedAt;

    @Version
    private Long version;

    private LocalDateTime deletedAt;

    /** 옵션 이름(색상·사이즈 등). 1번부터 채우고, 옵션이 없는 상품은 모두 null이다. 등록 뒤에는 바꾸지 않는다. */
    @Column(name = "option_name_1", length = MAX_OPTION_NAME_LENGTH)
    private String optionName1;

    @Column(name = "option_name_2", length = MAX_OPTION_NAME_LENGTH)
    private String optionName2;

    @Column(name = "option_name_3", length = MAX_OPTION_NAME_LENGTH)
    private String optionName3;

    /** 옵션 이름은 최대 3개(3단 조합)다. */
    public static final int MAX_OPTION_NAMES = 3;

    public static final int MAX_OPTION_NAME_LENGTH = 20;

    /** 옵션이 없는 상품을 만든다. 카테고리는 사용 중인 소분류여야 한다. */
    public static Product create(Long sellerId, Category category, String name, String description, long price) {
        return create(sellerId, category, name, description, price, List.of());
    }

    /**
     * 상품을 만든다. 카테고리는 사용 중인 소분류여야 한다.
     * 옵션 이름은 0~{@value #MAX_OPTION_NAMES}개, 각각 1~{@value #MAX_OPTION_NAME_LENGTH}자이고 서로 달라야 한다 (아니면 400).
     */
    public static Product create(Long sellerId, Category category, String name, String description, long price,
                                 List<String> optionNames) {
        category.checkSelectable();
        List<String> names = normalizeOptionNames(optionNames);

        Product product = new Product();
        product.sellerId = sellerId;
        product.category = category;
        product.name = name.strip();
        product.description = description.strip();
        product.price = price;
        product.status = ProductSaleStatus.ON_SALE;
        product.optionName1 = names.size() > 0 ? names.get(0) : null;
        product.optionName2 = names.size() > 1 ? names.get(1) : null;
        product.optionName3 = names.size() > 2 ? names.get(2) : null;
        return product;
    }

    private static List<String> normalizeOptionNames(List<String> optionNames) {
        if (optionNames.size() > MAX_OPTION_NAMES) {
            throw new DomainException(ProductErrorCode.OPTION_NAME_INVALID);
        }
        List<String> names = optionNames.stream().map(n -> n == null ? "" : n.strip()).toList();
        boolean invalid = names.stream().anyMatch(n -> n.isEmpty() || n.length() > MAX_OPTION_NAME_LENGTH);
        if (invalid || names.stream().distinct().count() != names.size()) {
            throw new DomainException(ProductErrorCode.OPTION_NAME_INVALID);
        }
        return names;
    }

    /** 옵션 이름 (순서대로). 옵션이 없는 상품이면 빈 목록 */
    public List<String> getOptionNames() {
        return Stream.of(optionName1, optionName2, optionName3).filter(Objects::nonNull).toList();
    }

    /**
     * 상품 정보를 수정한다. null이 아닌 값만 반영하고, 재고는 여기서 바꾸지 않는다.
     * 판매 중지·삭제된 상품이면 409, 바꿀 카테고리가 사용 중인 소분류가 아니면 400.
     */
    public void modify(String name, Long price, Category category, String description) {
        checkEditable();
        if (category != null) {
            category.checkSelectable();
            this.category = category;
        }
        if (name != null) this.name = name.strip();
        if (price != null) this.price = price;
        if (description != null) this.description = description.strip();
    }

    /**
     * 판매자 철회 요청·제재로 판매를 멈춘다 (stoppedBy = SELLER_WITHDRAWN). 판매 중인 상품에만 쓴다.
     * 철회가 거절되면 {@link #resumeFromSellerWithdrawal()}으로 되돌린다.
     */
    public void stopBySellerWithdrawal(String reason) {
        if (status != ProductSaleStatus.ON_SALE) {
            throw new IllegalStateException("판매 중인 상품만 중지할 수 있습니다. status=" + status);
        }
        stop(ProductStoppedBy.SELLER_WITHDRAWN, reason);
    }

    /** 관리자가 사유를 적고 바로 판매를 멈춘다 (stoppedBy = ADMIN, 해제 없음). 판매 중(품절 포함)이 아니면 409. */
    public void stopByAdmin(String reason) {
        checkOnSale();
        stop(ProductStoppedBy.ADMIN, reason.strip());
    }

    /**
     * 관리자가 판매자의 중지 요청을 승인해 판매를 멈춘다 (stoppedBy = SELLER_REQUEST, 사유는 판매자가 적은 사유).
     * 판매 중(품절 포함)이 아니면 409. {@link ProductStopRequest#approve()}가 부른다.
     */
    void stopBySellerRequest(String reason) {
        checkOnSale();
        stop(ProductStoppedBy.SELLER_REQUEST, reason);
    }

    private void checkOnSale() {
        if (status != ProductSaleStatus.ON_SALE) {
            throw new DomainException(ProductErrorCode.PRODUCT_NOT_ON_SALE);
        }
    }

    private void stop(ProductStoppedBy stoppedBy, String reason) {
        this.status = ProductSaleStatus.STOPPED;
        this.stoppedBy = stoppedBy;
        this.stoppedReason = reason;
        this.stoppedAt = LocalDateTime.now();
    }

    /**
     * 판매자가 상품을 지운다 (소프트 삭제). 판매 중(품절 포함)인 상품만 지울 수 있고, 판매 중지 상품이면 409.
     * 결제 대기 주문 취소와 심사 중인 중지 요청 정리는 app 계층이 함께 한다.
     */
    public void delete() {
        checkEditable();
        status = ProductSaleStatus.DELETED;
        deletedAt = LocalDateTime.now();
    }

    /** 판매자 철회로 멈춘 상품인지. 이 상품만 철회 거절 때 되돌린다. */
    public boolean isStoppedBySellerWithdrawal() {
        return status == ProductSaleStatus.STOPPED && stoppedBy == ProductStoppedBy.SELLER_WITHDRAWN;
    }

    /** 판매자 철회로 멈춘 상품을 다시 판매 중으로 되돌리고 중지 정보를 비운다. 관리자가 멈춘 상품에는 쓰지 않는다. */
    public void resumeFromSellerWithdrawal() {
        if (!isStoppedBySellerWithdrawal()) {
            throw new IllegalStateException("판매자 철회로 중지된 상품만 되돌릴 수 있습니다. status=" + status + ", stoppedBy=" + stoppedBy);
        }
        status = ProductSaleStatus.ON_SALE;
        stoppedBy = null;
        stoppedReason = null;
        stoppedAt = null;
    }

    public boolean isDeleted() {
        return status == ProductSaleStatus.DELETED;
    }

    public boolean isStopped() {
        return status == ProductSaleStatus.STOPPED;
    }

    /** 구매자에게 보여 줄 수 있는 상품인지. 판매 중이고 카테고리가 사용 중이어야 한다. 품절은 따로 본다. */
    public boolean isDisplayable() {
        return status == ProductSaleStatus.ON_SALE && category.isActive();
    }

    /** 판매 중지·삭제된 상품은 수정(재고 조정 포함)할 수 없다. */
    public void checkEditable() {
        if (isStopped() || isDeleted()) {
            throw new DomainException(ProductErrorCode.PRODUCT_NOT_EDITABLE);
        }
    }
}
