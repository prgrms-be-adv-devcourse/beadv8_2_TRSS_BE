package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductDiscount;
import com.backend.boundedContext.product.domain.ProductErrorCode;
import com.backend.boundedContext.product.domain.ProductPrice;
import com.backend.boundedContext.product.out.ProductDiscountRepository;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 상품 할인 조회와 검사. 가격은 조회 시각 기준(정가와 진행 중인 할인)이고, 할인 상태는 스케줄러 없이 조회할 때마다 계산한다.
 * 목록은 상품 ID를 모아 진행 중인 할인을 한 번에 읽는다.
 */
@Service
@RequiredArgsConstructor
public class ProductDiscountSupport {
    private final ProductDiscountRepository productDiscountRepository;

    /** 상품 ID → 지금 가격. 받은 상품마다 하나씩 있다 (할인이 없으면 정가만). */
    public Map<Long, ProductPrice> getPrices(Collection<Product> products) {
        if (products.isEmpty()) return Map.of();

        Set<Long> productIds = products.stream().map(Product::getId).collect(Collectors.toSet());
        Map<Long, ProductDiscount> discounts = productDiscountRepository
                .findAllActiveByProductIdIn(productIds, LocalDateTime.now()).stream()
                // 기간이 겹치지 않으므로 상품마다 하나지만, 혹시 겹친 데이터가 있어도 조회가 실패하지 않게 먼저 시작한 것을 쓴다
                .collect(Collectors.toMap(d -> d.getProduct().getId(), Function.identity(),
                        (a, b) -> a.getStartsAt().isAfter(b.getStartsAt()) ? b : a));

        return products.stream().collect(Collectors.toMap(Product::getId,
                product -> ProductPrice.of(product, discounts.get(product.getId())), (a, b) -> a));
    }

    /** 상품 하나의 지금 가격 */
    public ProductPrice getPrice(Product product) {
        return getPrices(Set.of(product)).get(product.getId());
    }

    /** 상품의 진행 중·예약 할인 (시작 시각 순). 끝난 할인은 판매자가 더 바꿀 수 없어 빼고, 이력이 쌓여도 읽는 양이 늘지 않는다 */
    public List<ProductDiscount> getPendingDiscounts(Long productId, LocalDateTime now) {
        return productDiscountRepository.findAllByProductIdAndDeletedAtIsNullAndEndsAtAfterOrderByStartsAtAsc(productId, now);
    }

    /** 상품의 삭제되지 않은 할인. 없거나 다른 상품의 할인이면 404 */
    public ProductDiscount getDiscount(Long productId, Long discountId) {
        return productDiscountRepository.findByIdAndProductIdAndDeletedAtIsNull(discountId, productId)
                .orElseThrow(() -> new DomainException(ProductErrorCode.DISCOUNT_NOT_FOUND));
    }

    /**
     * [startsAt, endsAt)이 상품의 다른 진행 중·예약 할인(excludeId 제외)과 겹치면 409.
     * 끝난 할인은 다시 적용되지 않으므로 보지 않는다. 그래서 지금 종료한 직후 같은 분에 '지금 시작'으로 등록해도 된다.
     * 상품을 쓰기 잠금한 뒤 불러야 동시에 등록해도 겹치지 않는다.
     */
    public void checkNoOverlap(Long productId, LocalDateTime startsAt, LocalDateTime endsAt, Long excludeId) {
        boolean overlapped = getPendingDiscounts(productId, LocalDateTime.now()).stream()
                .filter(discount -> !discount.getId().equals(excludeId))
                .anyMatch(discount -> discount.overlaps(startsAt, endsAt));
        if (overlapped) {
            throw new DomainException(ProductErrorCode.DISCOUNT_PERIOD_OVERLAPPED);
        }
    }

    /** 진행 중이거나 예약된 할인이 있으면 409 (정가를 바꾸기 전에 본다). 상품을 쓰기 잠금한 뒤 부른다. */
    public void checkNoPendingDiscount(Long productId) {
        if (productDiscountRepository.existsByProductIdAndDeletedAtIsNullAndEndsAtAfter(productId, LocalDateTime.now())) {
            throw new DomainException(ProductErrorCode.PRICE_LOCKED_BY_DISCOUNT);
        }
    }
}
