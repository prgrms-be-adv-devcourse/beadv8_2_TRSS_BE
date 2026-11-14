package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.Category;
import com.backend.boundedContext.product.domain.ProductErrorCode;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProductCreateCategoryUseCase {
    private final ProductSupport productSupport;
    private final CategoryRepository categoryRepository;

    /**
     * 소분류를 추가한다. 같은 대분류 아래 마지막 순서로 넣는다.
     */
    public Category createCategory(String name, Long parentId) {
        Category parent = productSupport.getCategory(parentId);
        Category category = Category.createChild(parent, name, nextSortOrder(parent));   // 부모가 소분류면 예외

        if (categoryRepository.existsByParentAndName(parent, category.getName())) {
            throw new DomainException(ProductErrorCode.CATEGORY_DUPLICATED);
        }
        return categoryRepository.save(category);
    }

    private int nextSortOrder(Category parent) {
        return categoryRepository.findTopByParentOrderBySortOrderDesc(parent)
                .map(last -> last.getSortOrder() + 1)
                .orElse(0);
    }
}
