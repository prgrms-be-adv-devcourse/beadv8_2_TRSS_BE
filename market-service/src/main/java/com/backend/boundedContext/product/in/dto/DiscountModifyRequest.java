package com.backend.boundedContext.product.in.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;

/**
 * 할인 수정 요청. 바꿀 값만 보내고, 보내지 않은 값은 그대로 둔다. 하나도 보내지 않으면 400.
 * 시작 전인 할인은 세 값 모두, 진행 중인 할인은 끝 시각만 바꿀 수 있다 (지금과 같은 값을 함께 보내는 것은 괜찮다).
 *
 * @param salePrice 할인가 (원). 정가의 10~99% (할인율 1~90%)
 * @param startsAt  시작 시각 (KST). 지금 이후 (지금이 속한 분까지 허용)
 * @param endsAt    끝 시각 (KST). 지금 이후이고 시작 시각 뒤 1년 이내. 이 시각부터 정가로 돌아간다
 */
public record DiscountModifyRequest(
        @Positive Long salePrice,
        @Schema(example = "2026-12-01T00:00:00") LocalDateTime startsAt,
        @Schema(example = "2026-12-15T00:00:00") LocalDateTime endsAt
) {
    @JsonIgnore
    @AssertTrue(message = "바꿀 값을 하나 이상 보내야 합니다.")
    public boolean isAnyValuePresent() {
        return salePrice != null || startsAt != null || endsAt != null;
    }
}
