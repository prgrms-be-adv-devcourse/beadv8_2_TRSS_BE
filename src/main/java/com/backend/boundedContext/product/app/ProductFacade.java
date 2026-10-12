package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.AdminCategoryTreeDto;
import com.backend.boundedContext.product.app.dto.CategoryDto;
import com.backend.boundedContext.product.app.dto.CategoryTreeDto;
import com.backend.boundedContext.product.domain.Category;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;


@Service
@RequiredArgsConstructor
public class ProductFacade {
    private final ProductSupport productSupport;
    private final ProductCreateCategoryUseCase productCreateCategoryUseCase;
    private final ProductModifyCategoryUseCase productModifyCategoryUseCase;

    @Transactional(readOnly = true)
    public List<CategoryTreeDto> getCategoryTree() {
        return productSupport.getCategoryTree();
    }

    @Transactional(readOnly = true)
    public List<AdminCategoryTreeDto> getCategoryTreeForAdmin() {
        return productSupport.getCategoryTreeForAdmin();
    }

    @Transactional
    public Long createCategory(String name, Long parentId) {
        return productCreateCategoryUseCase.createCategory(name, parentId).getId();
    }

    @Transactional
    public CategoryDto modifyCategory(Long categoryId, String name, Boolean active) {
        return CategoryDto.from(productModifyCategoryUseCase.modifyCategory(categoryId, name, active));
    }

    /**
     * 초기 카테고리를 넣는다. 카테고리가 하나라도 있으면 아무것도 하지 않는다.
     * 대분류 이름 → 소분류 이름 목록이며, 넣은 순서가 정렬 순서가 된다.
     */
    @Transactional
    public void createBaseCategories(Map<String, List<String>> baseCategories) {
        if (productSupport.countCategories() > 0) return;

        int rootOrder = 0;
        for (Map.Entry<String, List<String>> entry : baseCategories.entrySet()) {
            Category root = productCreateCategoryUseCase.createRootCategory(entry.getKey(), rootOrder++);
            for (String childName : entry.getValue()) {
                productCreateCategoryUseCase.createCategory(childName, root.getId());   // 넣은 순서대로 맨 뒤에 붙음
            }
        }
    }
}
