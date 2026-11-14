package com.backend.boundedContext.product.domain;

import com.backend.global.exception.DomainException;
import com.backend.global.jpa.entity.BaseIdAndTime;
import com.backend.shared.product.dto.ProductUnsaleableReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static jakarta.persistence.FetchType.LAZY;

/**
 * 상품 옵션. 장바구니에 담는 판매 단위(SKU)이고, 옵션 값의 조합 하나가 한 줄이다 (예: 흰색 / M).
 * 옵션 이름(색상·사이즈)은 상품에 있고, 여기에는 이름 순서대로 값만 둔다. 옵션이 없는 상품은 값이 없는 옵션 1개를 가진다.
 * 정가 단가는 상품 가격 + 추가금이고, 할인 중이면 할인가 + 추가금을 단가로 쓴다. 재고는 다른 행({@link OptionStock})에 둔다.
 */
@Entity
@Table(name = "product_option")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductOption extends BaseIdAndTime {

    /** 옵션이 없는 상품의 옵션 표시 이름. 화면에서는 옵션이 하나면 이름을 숨긴다. */
    public static final String DEFAULT_NAME = "기본";

    /** 표시 이름에서 값 사이에 넣는 구분자 */
    public static final String NAME_SEPARATOR = " / ";

    /**
     * 옵션 값 하나의 최대 길이. 주문·리뷰가 표시 이름을 option_name(varchar(50))에 저장하므로
     * 값 3개와 구분자 2개(14 x 3 + 6 = 48)가 50자를 넘지 않게 정했다.
     */
    public static final int MAX_VALUE_LENGTH = 14;

    /** 상품 하나에 둘 수 있는 삭제되지 않은 옵션 조합 수 */
    public static final int MAX_OPTIONS_PER_PRODUCT = 100;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "option_value_1", length = MAX_VALUE_LENGTH)
    private String optionValue1;

    @Column(name = "option_value_2", length = MAX_VALUE_LENGTH)
    private String optionValue2;

    @Column(name = "option_value_3", length = MAX_VALUE_LENGTH)
    private String optionValue3;

    /** 상품 가격에 더하는 금액 (0 이상) */
    @Column(nullable = false)
    private long additionalPrice;

    private LocalDateTime deletedAt;

    /** 옵션이 없는 상품의 옵션을 만든다. 재고는 옵션을 저장한 뒤 {@link OptionStock#init}으로 만든다. */
    public static ProductOption createDefault(Product product) {
        return create(product, List.of(), 0);
    }

    /**
     * 옵션 값 조합 하나를 만든다. 값은 상품의 옵션 이름 순서대로 하나씩, 각각 1~{@value #MAX_VALUE_LENGTH}자여야 한다 (아니면 400).
     * 같은 조합이 이미 있는지는 상품의 다른 옵션을 봐야 하므로 app 계층이 본다.
     */
    public static ProductOption create(Product product, List<String> values, long additionalPrice) {
        List<String> normalized = normalizeValues(values);
        if (normalized.size() != product.getOptionNames().size()) {
            throw new DomainException(ProductErrorCode.OPTION_VALUE_INVALID);
        }
        checkAdditionalPrice(additionalPrice);

        ProductOption option = new ProductOption();
        option.product = product;
        option.optionValue1 = normalized.size() > 0 ? normalized.get(0) : null;
        option.optionValue2 = normalized.size() > 1 ? normalized.get(1) : null;
        option.optionValue3 = normalized.size() > 2 ? normalized.get(2) : null;
        option.additionalPrice = additionalPrice;
        return option;
    }

    /** 앞뒤 공백을 지운 값. 비었거나 {@value #MAX_VALUE_LENGTH}자를 넘으면 400. 같은 조합인지 비교할 때도 쓴다. */
    public static List<String> normalizeValues(List<String> values) {
        List<String> normalized = values.stream().map(v -> v == null ? "" : v.strip()).toList();
        if (normalized.stream().anyMatch(v -> v.isEmpty() || v.length() > MAX_VALUE_LENGTH)) {
            throw new DomainException(ProductErrorCode.OPTION_VALUE_INVALID);
        }
        return normalized;
    }

    /** 옵션 값 (옵션 이름 순서대로). 옵션이 없는 상품의 옵션이면 빈 목록 */
    public List<String> getValues() {
        return Stream.of(optionValue1, optionValue2, optionValue3).filter(Objects::nonNull).toList();
    }

    /** 화면·주문·리뷰에 보여 줄 이름. 값을 " / "로 잇고(예: 흰색 / M), 값이 없으면 "기본" */
    public String getDisplayName() {
        List<String> values = getValues();
        return values.isEmpty() ? DEFAULT_NAME : String.join(NAME_SEPARATOR, values);
    }

    /** 이 옵션 하나의 정가 = 상품 가격 + 추가금. 할인을 적용한 단가는 {@link ProductPrice#unitPrice}로 구한다. */
    public long getListPrice() {
        return Math.addExact(product.getPrice(), additionalPrice);
    }

    /** 추가금을 바꾼다. 판매 중지·삭제된 상품이면 409(상품을 먼저 본다), 삭제된 옵션이면 409. */
    public void changeAdditionalPrice(long additionalPrice) {
        checkEditable();
        checkAdditionalPrice(additionalPrice);
        this.additionalPrice = additionalPrice;
    }

    /**
     * 옵션을 지운다 (소프트 삭제). 판매 중지·삭제된 상품이면 409, 이미 삭제된 옵션이면 409.
     * 마지막 옵션인지는 상품의 다른 옵션을 봐야 하므로 app 계층이 본다.
     */
    public void delete() {
        checkEditable();
        deletedAt = LocalDateTime.now();
    }

    private void checkEditable() {
        product.checkEditable();
        if (isDeleted()) {
            throw new DomainException(ProductErrorCode.OPTION_DELETED);
        }
    }

    private static void checkAdditionalPrice(long additionalPrice) {
        if (additionalPrice < 0) {
            throw new IllegalArgumentException("추가금은 0 이상이어야 합니다. additionalPrice=" + additionalPrice);
        }
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * 판매할 수 없는 이유. 판매할 수 있으면 null. 재고와는 무관하다.
     * 여러 개에 해당하면 삭제 → 중지 → 카테고리 순으로 하나만 준다 (주문 모듈이 삭제된 대상을 먼저 거를 수 있게).
     */
    public ProductUnsaleableReason getUnsaleableReason() {
        if (product.isDeleted()) return ProductUnsaleableReason.PRODUCT_DELETED;
        if (isDeleted()) return ProductUnsaleableReason.OPTION_DELETED;
        if (product.isStopped()) return ProductUnsaleableReason.PRODUCT_STOPPED;
        if (!product.getCategory().isActive()) return ProductUnsaleableReason.CATEGORY_INACTIVE;
        return null;
    }

    public boolean isSaleable() {
        return getUnsaleableReason() == null;
    }
}
