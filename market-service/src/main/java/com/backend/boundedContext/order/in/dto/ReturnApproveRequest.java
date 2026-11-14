package com.backend.boundedContext.order.in.dto;

import com.backend.boundedContext.order.domain.ReturnItemDecision;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 반품 승인 요청. 주문 항목마다 재고 복원 여부를 한 번씩 보낸다.
 */
public record ReturnApproveRequest(
        @NotEmpty List<@NotNull @Valid Item> items
) {
    /**
     * @param orderItemId  주문 항목 id
     * @param restoreStock true면 수량 전체를 재고로 되돌린다. 삭제된 상품·옵션은 true로 보낼 수 없다(409)
     */
    public record Item(
            @NotNull Long orderItemId,
            @NotNull Boolean restoreStock
    ) {
    }

    public List<ReturnItemDecision> toDecisions() {
        return items.stream()
                .map(item -> new ReturnItemDecision(item.orderItemId(), item.restoreStock()))
                .toList();
    }
}
