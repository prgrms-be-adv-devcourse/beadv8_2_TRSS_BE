package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.OrderErrorCode;
import com.backend.boundedContext.order.domain.OrderItem;
import com.backend.boundedContext.order.in.dto.SellerOrderItemResponse;
import com.backend.boundedContext.order.out.OrderItemRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.order.dto.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.backend.boundedContext.order.out.OrderItemSpecifications.keywordContains;
import static com.backend.boundedContext.order.out.OrderItemSpecifications.orderedFrom;
import static com.backend.boundedContext.order.out.OrderItemSpecifications.orderedTo;
import static com.backend.boundedContext.order.out.OrderItemSpecifications.sellerEquals;
import static com.backend.boundedContext.order.out.OrderItemSpecifications.statusIn;

/**
 * 판매자 주문 항목 조회
 */
@Component
@RequiredArgsConstructor
public class OrderGetSellerOrderItemsUseCase {

    // 판매자에게 보여줄 주문 상태
    static final List<OrderStatus> SELLER_VISIBLE_STATUSES = List.of(
            OrderStatus.PAID,
            OrderStatus.CONFIRMED,
            OrderStatus.RETURN_REQUESTED,
            OrderStatus.RETURNED
    );

    // 주문일 최신순,
    private static final Sort LATEST = Sort.by(Sort.Order.desc("order.createdAt"), Sort.Order.desc("id"));

    private final OrderItemRepository orderItemRepository;
    private final MemberApi memberApi;

    public PageResponse<SellerOrderItemResponse> getSellerOrderItems(Long memberId, OrderStatus status,
                                                                     LocalDate from, LocalDate to,
                                                                     String keyword, Pageable pageable) {
        validateStatus(status);
        validatePeriod(from, to);

        Pageable latestFirst = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST);

        Optional<SellerDto> seller = memberApi.findSellerByMemberId(memberId);
        if (seller.isEmpty()) {
            return PageResponse.from(Page.<SellerOrderItemResponse>empty(latestFirst));
        }

        Specification<OrderItem> spec = Specification.allOf(
                sellerEquals(seller.get().sellerId()),
                statusIn(status, SELLER_VISIBLE_STATUSES),
                orderedFrom(from),
                orderedTo(to),
                keywordContains(keyword)
        );

        Page<OrderItem> items = orderItemRepository.findAll(spec, latestFirst);

        return PageResponse.from(items, SellerOrderItemResponse::from);
    }

    // 결제 전·취소 상태로는 조회할 수 없다
    private void validateStatus(OrderStatus status) {
        if (status != null && !SELLER_VISIBLE_STATUSES.contains(status)) {
            throw new DomainException(OrderErrorCode.ORDER_STATUS_INVALID);
        }
    }

    // 조회기간 검증
    private void validatePeriod(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new DomainException(OrderErrorCode.ORDER_PERIOD_INVALID);
        }
    }
}
