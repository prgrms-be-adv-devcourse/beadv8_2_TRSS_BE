package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.Category;
import com.backend.boundedContext.product.domain.ProductErrorCode;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProductModifyCategoryUseCase {
    private final ProductSupport productSupport;
    private final CategoryRepository categoryRepository;

    /**
     * 소분류의 이름·사용 여부를 바꾼다. null인 값은 그대로 둔다.
     */
    public Category modifyCategory(Long categoryId, String name, Boolean active) {
        Category category = productSupport.getCategory(categoryId);
        category.checkModifiable();   // 대분류면 예외

        if (name != null && categoryRepository.existsByParentAndNameAndIdNot(
                category.getParent(), name.strip(), categoryId)) {
            throw new DomainException(ProductErrorCode.CATEGORY_DUPLICATED);
        }

        category.modify(name, active);
        return category;
    }
}
