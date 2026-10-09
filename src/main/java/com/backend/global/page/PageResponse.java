package com.backend.global.page;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * 목록 조회 응답의 공통 형식. RsData.data에 담아 응답한다.
 * 요청 파라미터는 page(0부터), size(기본 20, 최대 100)이며 Pageable로 받는다.
 *
 * <pre>
 * { "items": [...], "page": 0, "size": 20, "totalElements": 57, "totalPages": 3 }
 * </pre>
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages()
        );
    }

    /**
     * 엔티티 페이지를 DTO 페이지로 바꿔 응답할 때 사용한다.
     * 예: PageResponse.from(orderPage, Order::toDto)
     */
    public static <E, T> PageResponse<T> from(Page<E> page, Function<? super E, ? extends T> mapper) {
        return from(page.map(mapper));
    }
}
