package com.backend.boundedContext.order.out;

import com.backend.boundedContext.order.domain.Cart;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import com.backend.shared.order.dto.CartOptionDto;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {
    @EntityGraph(attributePaths = "items")
    Optional<Cart> findByMemberId(Long memberId);

    // 주문 생성 전에 장바구니 행을 잠금
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cart c where c.memberId = :memberId")
    Optional<Cart> findByMemberIdForUpdate(@Param("memberId") Long memberId);

    /** 회원 장바구니의 옵션과 담은 시각 (최근에 담은 순) */
    @Query("""
            select new com.backend.shared.order.dto.CartOptionDto(i.optionId, i.createdAt)
            from CartItem i
            where i.cart.memberId = :memberId
            order by i.createdAt desc
            """)
    List<CartOptionDto> findOptionsByMemberId(@Param("memberId") Long memberId);
}
