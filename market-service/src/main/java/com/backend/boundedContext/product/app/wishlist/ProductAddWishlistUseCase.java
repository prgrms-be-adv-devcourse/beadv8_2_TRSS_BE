package com.backend.boundedContext.product.app.wishlist;

import com.backend.boundedContext.product.app.ProductSupport;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductErrorCode;
import com.backend.boundedContext.product.out.wishlist.WishlistRepository;
import com.backend.global.eventPublisher.EventPublisher;
import com.backend.global.exception.DomainException;
import com.backend.shared.product.event.WishAddedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ProductAddWishlistUseCase {
    private final ProductSupport productSupport;
    private final WishlistRepository wishlistRepository;
    private final EventPublisher eventPublisher;

    /**
     * 상품을 찜한다. 구매자에게 보이지 않는 상품(판매 중지·삭제·카테고리 사용 중지)이면 404, 품절 상품은 찜할 수 있다.
     * 판매자 본인 상품이면 400 SELF_WISH_NOT_ALLOWED.
     * 이미 찜한 상품이면 아무것도 하지 않고 성공한다. 새로 찜했을 때만 이벤트를 발행한다.
     * 상품 행은 잠그거나 바꾸지 않는다.
     */
    public void addWishlist(Long memberId, Long productId) {
        Product product = productSupport.getDisplayableProduct(productId);

        if (productSupport.isOwnProduct(memberId, product)) {
            throw new DomainException(ProductErrorCode.SELF_WISH_NOT_ALLOWED);
        }

        if (wishlistRepository.insertIfAbsent(memberId, productId, LocalDateTime.now()) == 0) return;

        eventPublisher.publish(new WishAddedEvent(memberId, productId));
    }
}
