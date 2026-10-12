package com.backend.boundedContext.order.app;

import com.backend.boundedContext.order.domain.Cart;
import com.backend.boundedContext.order.out.CartRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 여러 UseCase가 함께 쓰는 조회.
 */
@Component
@RequiredArgsConstructor
public class OrderSupport {

    private final CartRepository cartRepository;

    // 회원 장바구니
    public Optional<Cart> findCart(Long memberId) {
        return cartRepository.findByMemberId(memberId);
    }

    // 회원의 장바구니가 없으면 새로 생성
    public Cart getOrCreateCart(Long memberId) {
        return cartRepository.findByMemberId(memberId)
                .orElseGet(() -> cartRepository.save(Cart.create(memberId)));
    }
}
