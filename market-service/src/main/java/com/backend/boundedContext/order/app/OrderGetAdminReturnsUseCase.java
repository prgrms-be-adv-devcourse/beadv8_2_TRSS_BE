package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.ReturnFilter;
import com.backend.boundedContext.order.in.dto.AdminReturnSummaryResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.out.MemberApi;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.backend.boundedContext.order.out.OrderSpecifications.returnFilter;

/**
 * 관리자 반품 목록
 * 처리 대기(REQUESTED)는 먼저 요청한 순, 나머지는 최근 요청 순. 요청의 sort 값은 무시한다.
 * 주문 항목은 Order.items의 @BatchSize로 한 페이지를 한 번에 읽는다(N+1 없음).
 * 구매자 이름은 페이지에 나온 구매자만 모아 회원 서비스에 한 번 조회한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderGetAdminReturnsUseCase {

    private static final Sort OLDEST_REQUEST_FIRST = Sort.by(Sort.Order.asc("returnRequestedAt"), Sort.Order.asc("id"));
    private static final Sort LATEST_REQUEST_FIRST = Sort.by(Sort.Order.desc("returnRequestedAt"), Sort.Order.desc("id"));

    private final OrderRepository orderRepository;
    private final MemberApi memberApi;

    public PageResponse<AdminReturnSummaryResponse> getReturns(ReturnFilter filter, Pageable pageable) {
        Sort sort = filter == ReturnFilter.REQUESTED ? OLDEST_REQUEST_FIRST : LATEST_REQUEST_FIRST;
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);

        Page<Order> orders = orderRepository.findAll(returnFilter(filter), sorted);
        Map<Long, String> buyerNames = getBuyerNames(orders.getContent());
        return PageResponse.from(orders, order -> AdminReturnSummaryResponse.of(order, buyerNames.get(order.getBuyerId())));
    }

    // 이름은 화면 표시용이라 회원 서비스 조회에 실패해도 목록은 이름 없이(null) 응답한다
    private Map<Long, String> getBuyerNames(List<Order> orders) {
        List<Long> buyerIds = orders.stream().map(Order::getBuyerId).distinct().toList();
        if (buyerIds.isEmpty()) {
            return Map.of();
        }

        try {
            // 이름이 null인 회원이 있어도 toMap처럼 터지지 않게 HashMap에 담는다
            Map<Long, String> names = new HashMap<>();
            memberApi.getMembers(buyerIds).forEach(member -> names.put(member.id(), member.name()));
            return names;
        } catch (RuntimeException e) {
            log.warn("회원 조회에 실패해 관리자 반품 목록을 구매자 이름 없이 응답합니다: buyerIds={}, reason={}", buyerIds, e.getMessage());
            return Map.of();
        }
    }
}
