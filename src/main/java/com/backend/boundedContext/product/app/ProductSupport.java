package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.AdminCategoryTreeDto;
import com.backend.boundedContext.product.app.dto.CategoryTreeDto;
import com.backend.boundedContext.product.domain.Category;
import com.backend.boundedContext.product.domain.ProductErrorCode;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
public class ProductSupport {
    private final CategoryRepository categoryRepository;

    public Category getCategory(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new DomainException(ProductErrorCode.CATEGORY_NOT_FOUND));
    }

    public long countCategories() {
        return categoryRepository.count();
    }

    /** 사용 중인 카테고리만 정렬 순서대로 트리로 만든다. */
    public List<CategoryTreeDto> getCategoryTree() {
        List<Category> categories = categoryRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .filter(Category::isActive)
                .toList();
        Map<Long, List<Category>> childrenByParentId = groupChildrenByParentId(categories);

        return categories.stream()
                .filter(Category::isRoot)
                .map(root -> new CategoryTreeDto(
                        root.getId(), root.getName(), root.getDepth(), root.getSortOrder(),
                        childrenByParentId.getOrDefault(root.getId(), List.of()).stream()
                                .map(child -> new CategoryTreeDto(
                                        child.getId(), child.getName(), child.getDepth(), child.getSortOrder(), List.of()))
                                .toList()))
                .toList();
    }

    /** 사용 중지된 카테고리까지 정렬 순서대로 트리로 만든다. */
    public List<AdminCategoryTreeDto> getCategoryTreeForAdmin() {
        List<Category> categories = categoryRepository.findAllByOrderBySortOrderAscIdAsc();
        Map<Long, List<Category>> childrenByParentId = groupChildrenByParentId(categories);

        return categories.stream()
                .filter(Category::isRoot)
                .map(root -> new AdminCategoryTreeDto(
                        root.getId(), root.getName(), root.getDepth(), root.getSortOrder(), root.isActive(),
                        childrenByParentId.getOrDefault(root.getId(), List.of()).stream()
                                .map(child -> new AdminCategoryTreeDto(
                                        child.getId(), child.getName(), child.getDepth(), child.getSortOrder(),
                                        child.isActive(), List.of()))
                                .toList()))
                .toList();
    }

    /** 정렬 순서를 유지한 채 소분류를 부모 ID별로 묶는다. */
    private Map<Long, List<Category>> groupChildrenByParentId(List<Category> categories) {
        return categories.stream()
                .filter(category -> !category.isRoot())
                .collect(Collectors.groupingBy(category -> category.getParent().getId()));
    }
}
