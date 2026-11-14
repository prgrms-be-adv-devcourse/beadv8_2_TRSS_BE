package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.boundedContext.order.domain.ReturnItemDecision;
import com.backend.boundedContext.order.in.dto.ReturnResultResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.global.exception.DomainException;
import com.backend.shared.order.event.OrderReturnedEvent;
import com.backend.shared.product.dto.StockChangeDto;
import com.backend.shared.product.dto.StockRestoreReason;
import com.backend.shared.product.out.ProductApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 관리자 반품 승인
 * 한 트랜잭션에서 주문을 RETURNED로 바꾸고, 복원을 고른 항목만 재고를 되돌리고(RETURN_RESTORE), 반품 승인 이벤트를 발행한다.
 * 환불은 결제 컨텍스트가 이벤트를 받아 처리한다. 쿠폰은 돌려주지 않으므로 쿠폰 해제를 부르지 않는다. 배송도 건드리지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderApproveReturnUseCase {

    private final OrderRepository orderRepository;
    private final ProductApi productApi;
    private final EventPublisher eventPublisher;

    public ReturnResultResponse approve(Long orderId, List<ReturnItemDecision> decisions) {
        // 잠금 순서: 상품 -> 주문 (상품 판매 중지·삭제가 상품을 잠근 뒤 주문을 잠그므로 반대로 잡으면 데드락)
        List<Long> productIds = orderRepository.findProductIdsByOrderId(orderId);
        if (productIds.isEmpty()) {
            throw new DomainException(OrderErrorCode.ORDER_NOT_FOUND);
        }
        productApi.lockProductsForOrderChange(productIds);

        // 다른 관리자의 승인·거절과 겹칠 수 있어 주문 행을 잠근 뒤 상태를 다시 본다. 뒤에 온 쪽은 409-ORDER_STATE_INVALID
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new DomainException(OrderErrorCode.ORDER_NOT_FOUND));

        LocalDateTime now = LocalDateTime.now();
        order.approveReturn(decisions, now);

        List<StockChangeDto> restoreItems = order.getItems().stream()
                .filter(item -> Boolean.TRUE.equals(item.getRestoreStock()))
                .map(OrderItem::toStockChangeDto)
                .toList();
        // 복원할 항목이 없으면 부르지 않는다(재고 이력도 남지 않음).
        // 삭제된 상품·옵션이면 product가 409-PRODUCT_DELETED·OPTION_DELETED를 던지고 주문 상태·이벤트까지 모두 롤백된다
        if (!restoreItems.isEmpty()) {
            productApi.restoreStock(restoreItems, StockRestoreReason.RETURN_RESTORE);
        }

        // 환불 금액 = 결제 금액 전액(배송비 포함). 같은 트랜잭션의 Outbox로 발행한다
        eventPublisher.publish(new OrderReturnedEvent(order.getId(), order.getBuyerId(), order.getPayAmount(), order.getReturnedAt()));

        log.info("반품 승인: orderId={}, itemCount={}, restoreItemCount={}, refundAmount={}",
                order.getId(), order.getItems().size(), restoreItems.size(), order.getPayAmount());
        return new ReturnResultResponse(order.getId(), order.getStatus(), null);
    }
}
