package com.backend.boundedContext.product.app.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 인기 검색어 한 줄
 *
 * @param rank       순위 (1부터)
 * @param keyword    검색어 (정규화한 값: 공백 정리, 영문 소문자)
 * @param rankChange 한 시간 전 순위와의 차이. 올라가면 양수, 내려가면 음수, 같으면 0. 한 시간 전 순위에 없던 검색어면 null(새로 오름)
 */
public record PopularKeywordDto(
        @Schema(description = "순위 (1부터)") int rank,
        @Schema(description = "검색어 (공백 정리, 영문 소문자)") String keyword,
        @Schema(description = "한 시간 전 순위와의 차이. 올라가면 양수, 내려가면 음수, 같으면 0, 새로 오른 검색어면 null", nullable = true)
        Integer rankChange
) {
}
