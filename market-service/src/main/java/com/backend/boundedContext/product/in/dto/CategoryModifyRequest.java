package com.backend.boundedContext.product.in.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 바꿀 값만 보낸다. 보내지 않은(null) 값은 그대로 둔다. 정렬 순서는 바꿀 수 없다.
 *
 * @param name   이름. 보낼 때는 공백만으로 이루어질 수 없다
 * @param active 사용 여부
 */
public record CategoryModifyRequest(
        @Size(max = 50) @Pattern(regexp = ".*\\S.*", message = "이름은 공백일 수 없습니다.") String name,
        Boolean active
) {
}
