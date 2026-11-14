package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.ReturnFilter;
import com.backend.boundedContext.order.in.dto.AdminReturnSummaryResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import static com.backend.boundedContext.order.out.OrderSpecifications.returnFilter;

/**
 * 관리자 반품 목록
 * 처리 대기(REQUESTED)는 먼저 요청한 순, 나머지는 최근 요청 순. 요청의 sort 값은 무시한다.
 * 주문 항목은 Order.items의 @BatchSize로 한 페이지를 한 번에 읽는다(N+1 없음).
 */
@Component
@RequiredArgsConstructor
public class OrderGetAdminReturnsUseCase {

    private static final Sort OLDEST_REQUEST_FIRST = Sort.by(Sort.Order.asc("returnRequestedAt"), Sort.Order.asc("id"));
    private static final Sort LATEST_REQUEST_FIRST = Sort.by(Sort.Order.desc("returnRequestedAt"), Sort.Order.desc("id"));

    private final OrderRepository orderRepository;

    public PageResponse<AdminReturnSummaryResponse> getReturns(ReturnFilter filter, Pageable pageable) {
        Sort sort = filter == ReturnFilter.REQUESTED ? OLDEST_REQUEST_FIRST : LATEST_REQUEST_FIRST;
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);

        Page<Order> orders = orderRepository.findAll(returnFilter(filter), sorted);
        return PageResponse.from(orders, AdminReturnSummaryResponse::from);
    }
}
