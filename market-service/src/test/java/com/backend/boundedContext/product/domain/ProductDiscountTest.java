package com.backend.boundedContext.product.domain;

import com.backend.global.exception.DomainException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 상품 기간 할인의 할인가·기간·상태 규칙과 할인 가격 계산 */
class ProductDiscountTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 11, 10, 30, 45);

    private final Category skincare = Category.createChild(Category.createRoot("뷰티", 0), "스킨케어", 0);

    private Product product(long price) {
        return Product.create(10L, skincare, "티셔츠", "설명", price, List.of("색상"));
    }

    private static ProductDiscount discount(Product product, long salePrice, LocalDateTime startsAt, LocalDateTime endsAt) {
        return ProductDiscount.create(product, salePrice, startsAt, endsAt, NOW);
    }

    private ProductDiscount scheduled() {
        return discount(product(20_000), 15_000, NOW.plusDays(1), NOW.plusDays(8));
    }

    /** 시작 전으로 만든 뒤 시작 시각만 과거로 옮겨 진행 중으로 만든다 (과거 시작은 등록할 수 없으므로) */
    private ProductDiscount active() {
        ProductDiscount discount = scheduled();
        ReflectionTestUtils.setField(discount, "startsAt", NOW.minusHours(1));
        return discount;
    }

    @Test
    @DisplayName("할인가는 정가의 10~99%만 된다 (정확한 비율로 본다)")
    void salePriceRange() {
        ProductDiscount.checkSalePrice(20_000, 2_000);    // 90% 할인
        ProductDiscount.checkSalePrice(20_000, 19_800);   // 1% 할인
        ProductDiscount.checkSalePrice(50, 5);     // 정가 50원은 5~49원 (90% 할인 ~ 2% 할인)
        ProductDiscount.checkSalePrice(50, 49);

        for (long[] invalid : new long[][]{{20_000, 1_999}, {20_000, 19_801}, {20_000, 20_000}, {20_000, 0}, {50, 4}, {50, 50}}) {
            assertCode(() -> ProductDiscount.checkSalePrice(invalid[0], invalid[1]), "400-DISCOUNT_PRICE_INVALID");
        }
    }

    @Test
    @DisplayName("할인율은 소수점을 내린다")
    void discountRateFloor() {
        assertThat(ProductDiscount.discountRate(30_000, 24_001)).isEqualTo(19);   // 19.99%
        assertThat(ProductDiscount.discountRate(20_000, 2_000)).isEqualTo(90);
        assertThat(ProductDiscount.discountRate(20_000, 19_800)).isEqualTo(1);
        assertThat(ProductDiscount.discountRate(50, 49)).isEqualTo(2);
    }

    @Test
    @DisplayName("기간: 시작이 지난 분이면, 끝이 시작 이전이면, 1년을 넘으면 DISCOUNT_PERIOD_INVALID")
    void period() {
        Product product = product(20_000);
        LocalDateTime start = NOW.plusDays(1);

        // 지금이 속한 분(10:30)까지는 지금으로 본다
        assertThat(discount(product, 15_000, NOW.withSecond(0), NOW.plusDays(1)).getStatus(NOW))
                .isEqualTo(ProductDiscountStatus.ACTIVE);
        assertThat(discount(product, 15_000, start, start.plusYears(1)).getEndsAt()).isEqualTo(start.plusYears(1));

        assertCode(() -> discount(product, 15_000, NOW.withSecond(0).minusSeconds(1), NOW.plusDays(1)), "400-DISCOUNT_PERIOD_INVALID");
        assertCode(() -> discount(product, 15_000, start, start), "400-DISCOUNT_PERIOD_INVALID");
        assertCode(() -> discount(product, 15_000, start, start.minusMinutes(1)), "400-DISCOUNT_PERIOD_INVALID");
        assertCode(() -> discount(product, 15_000, start, start.plusYears(1).plusSeconds(1)), "400-DISCOUNT_PERIOD_INVALID");
    }

    @Test
    @DisplayName("상태: 시작 전은 SCHEDULED, 시작~끝 전은 ACTIVE, 끝 시각부터 ENDED")
    void status() {
        ProductDiscount discount = scheduled();

        assertThat(discount.getStatus(NOW)).isEqualTo(ProductDiscountStatus.SCHEDULED);
        assertThat(discount.getStatus(NOW.plusDays(1))).isEqualTo(ProductDiscountStatus.ACTIVE);
        assertThat(discount.getStatus(NOW.plusDays(8).minusNanos(1))).isEqualTo(ProductDiscountStatus.ACTIVE);
        assertThat(discount.getStatus(NOW.plusDays(8))).isEqualTo(ProductDiscountStatus.ENDED);
    }

    @Test
    @DisplayName("기간 겹침: 끝 시각과 다음 시작 시각이 같으면 겹치지 않는다")
    void overlaps() {
        ProductDiscount discount = scheduled();   // [+1일, +8일)

        assertThat(discount.overlaps(NOW.plusDays(8), NOW.plusDays(9))).isFalse();
        assertThat(discount.overlaps(NOW, NOW.plusDays(1))).isFalse();
        assertThat(discount.overlaps(NOW.plusDays(7), NOW.plusDays(9))).isTrue();
        assertThat(discount.overlaps(NOW, NOW.plusDays(2))).isTrue();
        assertThat(discount.overlaps(NOW.plusDays(2), NOW.plusDays(3))).isTrue();
    }

    @Test
    @DisplayName("수정·삭제는 시작 전만, 종료는 진행 중만 된다")
    void transitions() {
        ProductDiscount scheduled = scheduled();
        scheduled.modify(14_000, NOW.plusDays(2), NOW.plusDays(3), NOW);
        assertThat(scheduled.getSalePrice()).isEqualTo(14_000);
        assertCode(() -> scheduled.end(NOW), "409-DISCOUNT_NOT_ACTIVE");
        scheduled.delete(NOW);
        assertThat(scheduled.isDeleted()).isTrue();

        ProductDiscount active = active();
        assertCode(() -> active.modify(14_000, NOW.plusDays(2), NOW.plusDays(3), NOW), "409-DISCOUNT_NOT_SCHEDULED");
        assertCode(() -> active.delete(NOW), "409-DISCOUNT_NOT_SCHEDULED");
        active.end(NOW);
        assertThat(active.getEndsAt()).isEqualTo(NOW);
        assertThat(active.getStatus(NOW)).isEqualTo(ProductDiscountStatus.ENDED);
        assertCode(() -> active.end(NOW), "409-DISCOUNT_NOT_ACTIVE");
    }

    @Test
    @DisplayName("판매 중지 상품은 할인을 등록·수정·종료할 수 없다 (PRODUCT_NOT_EDITABLE)")
    void stoppedProduct() {
        Product product = product(20_000);
        ProductDiscount scheduled = discount(product, 15_000, NOW.plusDays(1), NOW.plusDays(2));
        ProductDiscount active = active();
        ReflectionTestUtils.setField(product, "status", ProductSaleStatus.STOPPED);
        ReflectionTestUtils.setField(active.getProduct(), "status", ProductSaleStatus.STOPPED);

        assertCode(() -> discount(product, 15_000, NOW.plusDays(3), NOW.plusDays(4)), "409-PRODUCT_NOT_EDITABLE");
        assertCode(() -> scheduled.modify(14_000, NOW.plusDays(1), NOW.plusDays(2), NOW), "409-PRODUCT_NOT_EDITABLE");
        assertCode(() -> scheduled.delete(NOW), "409-PRODUCT_NOT_EDITABLE");
        assertCode(() -> active.end(NOW), "409-PRODUCT_NOT_EDITABLE");
    }

    @Test
    @DisplayName("가격: 할인은 기본가에만 적용하고 추가금은 그대로 더한다")
    void price() {
        ProductDiscount active = active();   // 정가 20,000 → 할인가 15,000
        ProductOption option = ProductOption.create(active.getProduct(), List.of("검정"), 3_000);

        ProductPrice discounted = ProductPrice.of(active.getProduct(), active);
        assertThat(discounted.salePrice()).isEqualTo(15_000);
        assertThat(discounted.discountRate()).isEqualTo(25);
        assertThat(discounted.discountEndsAt()).isEqualTo(NOW.plusDays(8));
        assertThat(discounted.unitPrice(option)).isEqualTo(18_000);
        assertThat(discounted.listUnitPrice(option)).isEqualTo(23_000);

        ProductPrice listOnly = ProductPrice.listOnly(active.getProduct());
        assertThat(listOnly.salePrice()).isNull();
        assertThat(listOnly.discountRate()).isNull();
        assertThat(listOnly.discountEndsAt()).isNull();
        assertThat(listOnly.unitPrice(option)).isEqualTo(23_000);
    }

    private static void assertCode(ThrowingCallable call, String resultCode) {
        assertThatThrownBy(call)
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo(resultCode);
    }
}
