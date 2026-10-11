package com.backend.boundedContext.product.app.dto;

import com.backend.boundedContext.product.domain.Category;

public record CategoryDto(
        Long categoryId,
        String name,
        int sortOrder,
        boolean active
) {
    public static CategoryDto from(Category category) {
        return new CategoryDto(category.getId(), category.getName(), category.getSortOrder(), category.isActive());
    }
}
