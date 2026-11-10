package com.backend.boundedContext.coupon.out;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link InfluencerPickPort} 임시 구현. pick 기능이 생기기 전까지 쓴다.
 *
 * <p>안전한 쪽으로 막는다: 어떤 상품도 pick하지 않은 것으로 답한다.
 * 그래서 상품 쿠폰 발행은 400-PRODUCT_NOT_PICKED가 되고, 협약 pick 상품 목록은 비어 있다.
 * pick이 구현되면 이 클래스를 지우고 pick을 조회하는 구현체로 바꾼다.
 */
@Component
public class InfluencerPickUnavailableAdapter implements InfluencerPickPort {

    @Override
    public boolean isPicked(Long influencerId, Long productId) {
        return false;
    }

    @Override
    public List<Long> findPickedProductIds(Long influencerId) {
        return List.of();
    }
}
