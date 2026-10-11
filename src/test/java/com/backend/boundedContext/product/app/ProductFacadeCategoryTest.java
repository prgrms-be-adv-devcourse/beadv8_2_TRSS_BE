package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.AdminCategoryTreeDto;
import com.backend.boundedContext.product.app.dto.CategoryTreeDto;
import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ProductDataInit이 넣은 초기 카테고리(뷰티·의류 각 8개)를 기준으로 검증한다.
 * 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
class ProductFacadeCategoryTest {

    @Autowired
    private ProductFacade productFacade;

    @Test
    @DisplayName("초기 데이터로 대분류 2개, 소분류 8개씩 들어 있다")
    void baseCategories() {
        List<CategoryTreeDto> tree = productFacade.getCategoryTree();

        assertThat(tree).extracting(CategoryTreeDto::name).containsExactly("뷰티", "의류");
        assertThat(tree).allSatisfy(root -> assertThat(root.children()).hasSize(8));
        assertThat(tree.getFirst().children().getFirst().name()).isEqualTo("메이크업");
    }

    @Test
    @DisplayName("초기 데이터를 다시 넣어도 중복으로 들어가지 않는다")
    void createBaseCategoriesTwice() {
        productFacade.createBaseCategories(Map.of("테스트", List.of("하위")));

        assertThat(productFacade.getCategoryTreeForAdmin()).hasSize(2);
    }

    @Test
    @DisplayName("sortOrder 없이 추가하면 같은 대분류의 마지막 순서로 들어간다")
    void createCategoryAtLast() {
        Long beautyId = rootId("뷰티");

        productFacade.createCategory("향수", beautyId, null);

        List<CategoryTreeDto> children = root("뷰티").children();
        assertThat(children.getLast().name()).isEqualTo("향수");
        assertThat(children.getLast().sortOrder()).isEqualTo(8);
    }

    @Test
    @DisplayName("같은 대분류 아래 같은 이름은 CATEGORY_DUPLICATED, 다른 대분류면 허용")
    void createDuplicated() {
        assertThatThrownBy(() -> productFacade.createCategory("스킨케어", rootId("뷰티"), null))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-CATEGORY_DUPLICATED");

        productFacade.createCategory("스킨케어", rootId("의류"), null);
    }

    @Test
    @DisplayName("없는 부모로 추가하면 CATEGORY_NOT_FOUND")
    void createWithUnknownParent() {
        assertThatThrownBy(() -> productFacade.createCategory("향수", Long.MAX_VALUE, null))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("404-CATEGORY_NOT_FOUND");
    }

    @Test
    @DisplayName("사용 중지한 소분류는 구매자용 트리에서 빠지고 관리자용 트리에는 남는다")
    void deactivate() {
        Long skincareId = childId("뷰티", "스킨케어");

        productFacade.modifyCategory(skincareId, null, null, false);

        assertThat(root("뷰티").children()).extracting(CategoryTreeDto::name).doesNotContain("스킨케어");
        assertThat(productFacade.getCategoryTreeForAdmin().getFirst().children())
                .filteredOn(c -> c.categoryId().equals(skincareId))
                .singleElement()
                .extracting(AdminCategoryTreeDto::active).isEqualTo(false);
    }

    @Test
    @DisplayName("이름을 같은 대분류의 다른 소분류 이름으로 바꾸면 CATEGORY_DUPLICATED, 자기 이름 그대로면 허용")
    void modifyDuplicated() {
        Long skincareId = childId("뷰티", "스킨케어");

        assertThatThrownBy(() -> productFacade.modifyCategory(skincareId, "메이크업", null, null))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("409-CATEGORY_DUPLICATED");

        assertThat(productFacade.modifyCategory(skincareId, "스킨케어", 0, null).sortOrder()).isZero();
    }

    @Test
    @DisplayName("대분류를 수정하면 CATEGORY_NOT_EDITABLE")
    void modifyRoot() {
        assertThatThrownBy(() -> productFacade.modifyCategory(rootId("뷰티"), null, null, false))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-CATEGORY_NOT_EDITABLE");
    }

    @Test
    @DisplayName("소분류 아래에 추가하면 CATEGORY_DEPTH_EXCEEDED")
    void createUnderChild() {
        assertThatThrownBy(() -> productFacade.createCategory("토너", childId("뷰티", "스킨케어"), null))
                .isInstanceOf(DomainException.class)
                .extracting("resultCode").isEqualTo("400-CATEGORY_DEPTH_EXCEEDED");
    }

    private CategoryTreeDto root(String name) {
        return productFacade.getCategoryTree().stream()
                .filter(root -> root.name().equals(name))
                .findFirst().orElseThrow();
    }

    private Long rootId(String name) {
        return root(name).categoryId();
    }

    private Long childId(String rootName, String childName) {
        return root(rootName).children().stream()
                .filter(child -> child.name().equals(childName))
                .findFirst().orElseThrow()
                .categoryId();
    }
}
