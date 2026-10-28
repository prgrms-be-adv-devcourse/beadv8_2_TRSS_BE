package com.backend.boundedContext.order.in;

import com.backend.boundedContext.order.app.OrderFacade;
import com.backend.boundedContext.order.domain.OrderPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderSchedulerTest {

    @Mock OrderFacade orderFacade;

    OrderScheduler scheduler;

    @BeforeEach
    void setUp() {
        // 한 번에 2건씩 조회하도록 batchSize 2
        scheduler = new OrderScheduler(orderFacade, new OrderPolicy(15, 7, 99, 2, 1));
    }

    @Test
    @DisplayName("대상을 batchSize건씩 마지막 id 다음부터 끝까지 조회하고, 조회 결과가 batchSize보다 적으면 멈춘다")
    void autoConfirmOrders_paging() {
        given(orderFacade.findAutoConfirmTargetIds(any(LocalDateTime.class), eq(0L), eq(2))).willReturn(List.of(1L, 2L));
        given(orderFacade.findAutoConfirmTargetIds(any(LocalDateTime.class), eq(2L), eq(2))).willReturn(List.of(3L));
        given(orderFacade.autoConfirmOrder(anyLong(), any(LocalDateTime.class))).willReturn(true);

        scheduler.autoConfirmOrders();

        verify(orderFacade).autoConfirmOrder(eq(1L), any(LocalDateTime.class));
        verify(orderFacade).autoConfirmOrder(eq(2L), any(LocalDateTime.class));
        verify(orderFacade).autoConfirmOrder(eq(3L), any(LocalDateTime.class));
        verify(orderFacade, never()).findAutoConfirmTargetIds(any(LocalDateTime.class), eq(3L), eq(2));
    }

    @Test
    @DisplayName("한 건이 실패해도 다음 주문을 계속 확정한다")
    void autoConfirmOrders_continueOnFailure() {
        given(orderFacade.findAutoConfirmTargetIds(any(LocalDateTime.class), eq(0L), eq(2))).willReturn(List.of(1L));
        given(orderFacade.autoConfirmOrder(eq(1L), any(LocalDateTime.class))).willThrow(new RuntimeException("DB 오류"));

        scheduler.autoConfirmOrders();   // 예외가 밖으로 나오지 않는다

        verify(orderFacade).autoConfirmOrder(eq(1L), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("여러 건 중 하나가 실패해도 나머지는 확정한다")
    void autoConfirmOrders_partialFailure() {
        given(orderFacade.findAutoConfirmTargetIds(any(LocalDateTime.class), eq(0L), eq(2))).willReturn(List.of(1L, 2L));
        given(orderFacade.findAutoConfirmTargetIds(any(LocalDateTime.class), eq(2L), eq(2))).willReturn(List.of());
        given(orderFacade.autoConfirmOrder(eq(1L), any(LocalDateTime.class))).willThrow(new RuntimeException("DB 오류"));
        given(orderFacade.autoConfirmOrder(eq(2L), any(LocalDateTime.class))).willReturn(true);

        scheduler.autoConfirmOrders();

        verify(orderFacade).autoConfirmOrder(eq(2L), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("결제 만료: 대상을 batchSize건씩 끝까지 조회해 한 건씩 만료 취소한다")
    void expireOrders_paging() {
        given(orderFacade.findExpireTargetIds(any(LocalDateTime.class), eq(0L), eq(2))).willReturn(List.of(1L, 2L));
        given(orderFacade.findExpireTargetIds(any(LocalDateTime.class), eq(2L), eq(2))).willReturn(List.of(3L));
        given(orderFacade.expireOrder(anyLong(), any(LocalDateTime.class))).willReturn(true);

        scheduler.expireOrders();

        verify(orderFacade).expireOrder(eq(1L), any(LocalDateTime.class));
        verify(orderFacade).expireOrder(eq(2L), any(LocalDateTime.class));
        verify(orderFacade).expireOrder(eq(3L), any(LocalDateTime.class));
        verify(orderFacade, never()).findExpireTargetIds(any(LocalDateTime.class), eq(3L), eq(2));
        verify(orderFacade, never()).autoConfirmOrder(anyLong(), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("결제 만료: 여러 건 중 하나가 실패해도 나머지는 취소한다")
    void expireOrders_partialFailure() {
        given(orderFacade.findExpireTargetIds(any(LocalDateTime.class), eq(0L), eq(2))).willReturn(List.of(1L, 2L));
        given(orderFacade.findExpireTargetIds(any(LocalDateTime.class), eq(2L), eq(2))).willReturn(List.of());
        given(orderFacade.expireOrder(eq(1L), any(LocalDateTime.class))).willThrow(new RuntimeException("재고 복원 실패"));
        given(orderFacade.expireOrder(eq(2L), any(LocalDateTime.class))).willReturn(true);

        scheduler.expireOrders();

        verify(orderFacade).expireOrder(eq(2L), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("결제 만료: 대상 조회가 실패해도 예외가 밖으로 나오지 않는다")
    void expireOrders_findFails() {
        given(orderFacade.findExpireTargetIds(any(LocalDateTime.class), eq(0L), eq(2)))
                .willThrow(new RuntimeException("DB 연결 실패"));

        scheduler.expireOrders();   // 예외가 밖으로 나오지 않는다

        verify(orderFacade, never()).expireOrder(anyLong(), any(LocalDateTime.class));
    }
}
