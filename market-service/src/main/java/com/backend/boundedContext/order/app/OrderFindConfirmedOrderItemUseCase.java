package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.out.OrderItemRepository;
import com.backend.shared.order.dto.OrderStatus;
import com.backend.shared.order.dto.ReviewableOrderItemDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 구매자 본인의 구매 확정 주문 항목 조회 (리뷰 작성 자격 확인용, 다른 컨텍스트 공개용)
 */
@Component
@RequiredArgsConstructor
public class OrderFindConfirmedOrderItemUseCase {

    private final OrderItemRepository orderItemRepository;

    public Optional<ReviewableOrderItemDto> find(Long buyerId, Long orderItemId) {
        if (buyerId == null || orderItemId == null) {
            return Optional.empty();
        }
        return orderItemRepository.findReviewableItem(buyerId, orderItemId, OrderStatus.CONFIRMED);
    }
}
