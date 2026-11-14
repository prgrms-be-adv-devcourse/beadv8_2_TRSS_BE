package com.backend.boundedContext.product.app.wishlist;

import com.backend.boundedContext.product.out.wishlist.WishlistRepository;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.shared.product.event.WishRemovedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProductRemoveWishlistUseCase {
    private final WishlistRepository wishlistRepository;
    private final EventPublisher eventPublisher;

    /**
     * 찜을 해제한다. 판매 중지·삭제된 상품도 해제할 수 있다.
     * 찜하지 않은 상품(없는 상품 포함)이면 아무것도 하지 않고 성공한다. 실제로 지웠을 때만 이벤트를 발행한다.
     */
    public void removeWishlist(Long memberId, Long productId) {
        if (wishlistRepository.deleteByMemberIdAndProductId(memberId, productId) == 0) return;

        eventPublisher.publish(new WishRemovedEvent(memberId, productId));
    }
}
