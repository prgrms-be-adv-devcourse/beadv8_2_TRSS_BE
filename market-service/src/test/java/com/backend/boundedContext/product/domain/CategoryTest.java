package com.backend.boundedContext.product.domain;

import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CategoryTest {

    @Test
    @DisplayName("대분류 아래에 소분류를 만들면 깊이 2, 사용 중 상태가 된다")
    void createChild() {
        Category root = Category.createRoot("뷰티", 0);

        Category child = Category.createChild(root, "  스킨케어 ", 3);

        assertThat(child.getDepth()).isEqualTo(2);
        assertThat(child.getName()).isEqualTo("스킨케어");
        assertThat(child.getSortOrder()).isEqualTo(3);
        assertThat(child.isActive()).isTrue();
        assertThat(child.getParent()).isSameAs(root);
    }

    @Test
    @DisplayName("소분류 아래에 카테고리를 만들면 CATEGORY_DEPTH_EXCEEDED")
    void createChildUnderChild() {
        Category child = Category.createChild(Category.createRoot("뷰티", 0), "스킨케어", 0);

        assertThatThrownBy(() -> Category.createChild(child, "토너", 0))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-CATEGORY_DEPTH_EXCEEDED");
    }

    @Test
    @DisplayName("소분류는 보낸 값만 바뀐다")
    void modifyOnlyGivenValues() {
        Category child = Category.createChild(Category.createRoot("뷰티", 0), "스킨케어", 0);

        child.modify(null, false);

        assertThat(child.getName()).isEqualTo("스킨케어");
        assertThat(child.isActive()).isFalse();

        child.modify(" 토너 ", null);

        assertThat(child.getName()).isEqualTo("토너");
        assertThat(child.isActive()).isFalse();
    }

    @Test
    @DisplayName("대분류를 수정하면 CATEGORY_NOT_EDITABLE")
    void modifyRoot() {
        Category root = Category.createRoot("뷰티", 0);

        assertThatThrownBy(() -> root.modify("화장품", null))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-CATEGORY_NOT_EDITABLE");
        assertThat(root.getName()).isEqualTo("뷰티");
    }
}
