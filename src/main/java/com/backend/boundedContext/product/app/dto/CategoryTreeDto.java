package com.backend.boundedContext.product.app.dto;

import java.util.List;

/** 구매자용 카테고리 트리 노드. 사용 중인 카테고리만 담는다. */
public record CategoryTreeDto(
        Long categoryId,
        String name,
        int depth,
        int sortOrder,
        List<CategoryTreeDto> children
) {
}
