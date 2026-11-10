package com.backend.boundedContext.product.domain;

import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 상품의 옵션 이름과 옵션 조합(값·추가금·표시 이름·단가) 규칙 */
class ProductOptionCombinationTest {

    private final Category skincare = Category.createChild(Category.createRoot("뷰티", 0), "스킨케어", 0);

    private Product product(String... optionNames) {
        return Product.create(10L, skincare, "티셔츠", "설명", 20_000, List.of(optionNames));
    }

    @Test
    @DisplayName("옵션 이름은 앞뒤 공백을 지워 순서대로 저장하고, 없으면 빈 목록이다")
    void optionNames() {
        assertThat(product(" 색상 ", "사이즈").getOptionNames()).containsExactly("색상", "사이즈");
        assertThat(Product.create(10L, skincare, "크림", "설명", 20_000).getOptionNames()).isEmpty();
    }

    @Test
    @DisplayName("옵션 이름이 4개 이상이거나, 비었거나, 20자를 넘거나, 겹치면 OPTION_NAME_INVALID")
    void invalidOptionNames() {
        List<List<String>> invalids = List.of(
                List.of("a", "b", "c", "d"), List.of(" "), List.of("가".repeat(21)), List.of("색상", " 색상"),
                Arrays.asList("색상", null));
        for (List<String> names : invalids) {
            assertThatThrownBy(() -> Product.create(10L, skincare, "티셔츠", "설명", 20_000, names))
                    .isInstanceOf(DomainException.class)
                    .extracting("resultCode").isEqualTo("400-OPTION_NAME_INVALID");
        }
    }

    @Test
    @DisplayName("조합은 값을 ' / '로 이은 표시 이름을 갖고, 단가는 상품 가격 + 추가금이다")
    void combination() {
        ProductOption option = ProductOption.create(product("색상", "사이즈", "소재"), List.of(" 흰색", "M", "면"), 3_000);

        assertThat(option.getValues()).containsExactly("흰색", "M", "면");
        assertThat(option.getDisplayName()).isEqualTo("흰색 / M / 면");
        assertThat(option.getAdditionalPrice()).isEqualTo(3_000);
        assertThat(option.getUnitPrice()).isEqualTo(23_000);
    }

    @Test
    @DisplayName("옵션이 없는 상품의 옵션은 값이 없고 표시 이름은 '기본', 단가는 상품 가격이다")
    void defaultOption() {
        ProductOption option = ProductOption.createDefault(product());

        assertThat(option.getValues()).isEmpty();
        assertThat(option.getDisplayName()).isEqualTo("기본");
        assertThat(option.getUnitPrice()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("값 3개(각 14자)의 표시 이름은 주문·리뷰의 option_name(50자)에 들어간다")
    void longestDisplayNameFits() {
        String value = "가".repeat(ProductOption.MAX_VALUE_LENGTH);
        ProductOption option = ProductOption.create(product("a", "b", "c"), List.of(value, value, value), 0);

        assertThat(option.getDisplayName()).hasSizeLessThanOrEqualTo(50);
    }

    @Test
    @DisplayName("값 개수가 옵션 이름 개수와 다르거나, 값이 비었거나 14자를 넘으면 OPTION_VALUE_INVALID")
    void invalidValues() {
        Product product = product("색상", "사이즈");
        List<List<String>> invalids = List.of(
                List.of("흰색"), List.of("흰색", "M", "면"), List.of("흰색", " "), List.of("흰색", "가".repeat(15)));
        for (List<String> values : invalids) {
            assertThatThrownBy(() -> ProductOption.create(product, values, 0))
                    .isInstanceOf(DomainException.class)
                    .extracting("resultCode").isEqualTo("400-OPTION_VALUE_INVALID");
        }
    }

    @Test
    @DisplayName("추가금은 바꿀 수 있고, 삭제된 옵션이나 판매 중지 상품이면 409")
    void changeAdditionalPrice() {
        Product product = product("색상");
        ProductOption option = ProductOption.create(product, List.of("흰색"), 0);

        option.changeAdditionalPrice(1_000);
        assertThat(option.getUnitPrice()).isEqualTo(21_000);

        option.delete();
        assertThatThrownBy(() -> option.changeAdditionalPrice(2_000))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-OPTION_DELETED");

        ProductOption other = ProductOption.create(product, List.of("검정"), 0);
        ReflectionTestUtils.setField(product, "status", ProductSaleStatus.STOPPED);
        assertThatThrownBy(() -> other.changeAdditionalPrice(2_000))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-PRODUCT_NOT_EDITABLE");
    }

    @Test
    @DisplayName("이미 삭제된 옵션을 다시 지우면 OPTION_DELETED이고 삭제 시각은 그대로다")
    void deleteTwice() {
        ProductOption option = ProductOption.create(product("색상"), List.of("흰색"), 0);
        option.delete();
        LocalDateTime deletedAt = option.getDeletedAt();

        assertThatThrownBy(option::delete)
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-OPTION_DELETED");
        assertThat(option.getDeletedAt()).isEqualTo(deletedAt);
    }
}
