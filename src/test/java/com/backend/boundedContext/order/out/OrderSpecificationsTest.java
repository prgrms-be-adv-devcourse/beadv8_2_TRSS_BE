package com.backend.boundedContext.order.out;

import com.backend.boundedContext.order.domain.Order;
import com.backend.shared.order.dto.OrderStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderSpecificationsTest {

    @Mock Root<Order> root;
    @Mock CriteriaQuery<?> query;
    @Mock CriteriaBuilder cb;
    @Mock Path<Object> statusPath;
    @Mock Predicate predicate;

    @Test
    @DisplayName("상태를 여러 개 주면 그중 하나인 주문을 찾는 조건이 된다")
    void statusIn() {
        List<OrderStatus> statuses = List.of(OrderStatus.CREATED, OrderStatus.PAYMENT_FAILED);
        given(root.get("status")).willReturn(statusPath);
        given(statusPath.in(statuses)).willReturn(predicate);

        Predicate result = OrderSpecifications.statusIn(statuses).toPredicate(root, query, cb);

        assertThat(result).isSameAs(predicate);
    }

    @Test
    @DisplayName("상태를 주지 않거나 빈 목록이면 상태 조건을 걸지 않는다")
    void statusInEmpty() {
        OrderSpecifications.statusIn(null).toPredicate(root, query, cb);
        OrderSpecifications.statusIn(List.of()).toPredicate(root, query, cb);

        verifyNoInteractions(root);
    }
}
