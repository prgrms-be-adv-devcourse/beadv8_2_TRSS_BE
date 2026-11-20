package com.backend.boundedContext.product.app.dto;

import java.util.List;

/** 관리자용 카테고리 트리 노드. 사용 중지된 카테고리도 담는다. */
public record AdminCategoryTreeDto(
        Long categoryId,
        String name,
        int depth,
        int sortOrder,
        boolean active,
        List<AdminCategoryTreeDto> children
) {
}
