package com.backend.boundedContext.product.in.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;

/**
 * 할인 등록·수정 요청. 수정할 때도 세 값을 모두 보낸다.
 *
 * @param salePrice 할인가 (원). 정가의 10~99% (할인율 1~90%)
 * @param startsAt  시작 시각 (KST). 지금 이후 (지금이 속한 분까지 허용)
 * @param endsAt    끝 시각 (KST). 시작 시각 뒤 1년 이내. 이 시각부터 정가로 돌아간다
 */
public record DiscountRequest(
        @NotNull @Positive Long salePrice,
        @Schema(example = "2026-12-01T00:00:00") @NotNull LocalDateTime startsAt,
        @Schema(example = "2026-12-08T00:00:00") @NotNull LocalDateTime endsAt
) {
}
