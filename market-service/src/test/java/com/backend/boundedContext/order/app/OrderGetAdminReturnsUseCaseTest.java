package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Order;
import com.backend.boundedContext.order.domain.OrderItemSpec;
import com.backend.boundedContext.order.domain.ReturnFilter;
import com.backend.boundedContext.order.in.dto.AdminReturnSummaryResponse;
import com.backend.boundedContext.order.out.OrderRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.global.page.PageResponse;
import com.backend.shared.member.dto.MemberDto;
import com.backend.shared.member.dto.MemberRole;
import com.backend.shared.member.dto.MemberStatus;
import com.backend.shared.member.out.MemberApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderGetAdminReturnsUseCaseTest {

    @Mock OrderRepository orderRepository;
    @Mock MemberApi memberApi;

    @InjectMocks OrderGetAdminReturnsUseCase useCase;

    private static final Pageable PAGE = PageRequest.of(0, 20);
    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 1, 10, 0);

    private static Order order(long orderId, long buyerId) {
        Order order = Order.create(buyerId, List.of(
                new OrderItemSpec(null, 10L, 100L, 2L, "약산성 토너", "300ml", "온결", 12_000, 1)), AT, 15);
        ReflectionTestUtils.setField(order, "id", orderId);
        return order;
    }

    private static MemberDto member(long id, String name) {
        return new MemberDto(id, "m" + id + "@test.com", name, MemberRole.USER, MemberStatus.ACTIVE);
    }

    @SuppressWarnings("unchecked")
    private void givenOrders(Order... orders) {
        Page<Order> page = new PageImpl<>(List.of(orders), PAGE, orders.length);
        given(orderRepository.findAll(any(Specification.class), any(Pageable.class))).willReturn(page);
    }

    private List<String> buyerNames(PageResponse<AdminReturnSummaryResponse> response) {
        return response.items().stream().map(AdminReturnSummaryResponse::buyerName).toList();
    }

    @Test
    @DisplayName("페이지의 구매자 id를 중복 없이 모아 회원 서비스에 한 번 조회하고 이름을 붙인다")
    void mapsBuyerNamesWithOneCall() {
        givenOrders(order(1L, 4L), order(2L, 5L), order(3L, 4L));
        given(memberApi.getMembers(anyCollection())).willReturn(List.of(member(4L, "김구매"), member(5L, "이구매")));

        PageResponse<AdminReturnSummaryResponse> response = useCase.getReturns(ReturnFilter.REQUESTED, PAGE);

        assertThat(buyerNames(response)).containsExactly("김구매", "이구매", "김구매");
        verify(memberApi, times(1)).getMembers(argThat((Collection<Long> ids) ->
                ids.size() == 2 && ids.containsAll(List.of(4L, 5L))));
    }

    @Test
    @DisplayName("회원 서비스 조회가 실패하면 구매자 이름은 모두 null이고 목록은 정상 응답한다")
    void memberApiFails_namesNull() {
        givenOrders(order(1L, 4L), order(2L, 5L));
        given(memberApi.getMembers(anyCollection()))
                .willThrow(new DomainException(GlobalErrorCode.EXTERNAL_SERVICE_ERROR));

        PageResponse<AdminReturnSummaryResponse> response = useCase.getReturns(ReturnFilter.ALL, PAGE);

        assertThat(response.items()).extracting(AdminReturnSummaryResponse::orderId).containsExactly(1L, 2L);
        assertThat(buyerNames(response)).containsExactly(null, null);
    }

    @Test
    @DisplayName("회원 목록에 없는 구매자(탈퇴 등)는 이름이 null이다")
    void missingMember_nameNull() {
        givenOrders(order(1L, 4L), order(2L, 99L));
        given(memberApi.getMembers(anyCollection())).willReturn(List.of(member(4L, "김구매")));

        PageResponse<AdminReturnSummaryResponse> response = useCase.getReturns(ReturnFilter.ALL, PAGE);

        assertThat(buyerNames(response)).containsExactly("김구매", null);
        assertThat(response.items()).extracting(AdminReturnSummaryResponse::buyerId).containsExactly(4L, 99L);
    }

    @Test
    @DisplayName("빈 페이지면 회원 서비스를 부르지 않는다")
    void emptyPage_noMemberCall() {
        givenOrders();

        PageResponse<AdminReturnSummaryResponse> response = useCase.getReturns(ReturnFilter.ALL, PAGE);

        assertThat(response.items()).isEmpty();
        verifyNoInteractions(memberApi);
    }
}
