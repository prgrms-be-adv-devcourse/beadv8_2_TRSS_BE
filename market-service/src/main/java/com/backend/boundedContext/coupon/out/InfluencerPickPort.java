package com.backend.boundedContext.coupon.out;

import java.util.List;

/**
 * 쿠폰(상품 쿠폰 발행, 협약 pick 상품 목록)이 쓰는 인플루언서 pick 기능. pick은 팔레트 쪽에서 관리한다.
 *
 * <p>pick 기능이 생기기 전까지는 {@link InfluencerPickUnavailableAdapter}가 "pick 안 함"으로 답한다.
 * pick이 구현되면 이 포트의 구현체만 바꾼다.
 */
public interface InfluencerPickPort {

    /** 이 인플루언서가 이 상품을 pick했는지 */
    boolean isPicked(Long influencerId, Long productId);

    /** 이 인플루언서가 pick한 상품 ID (상태와 상관없이 모두) */
    List<Long> findPickedProductIds(Long influencerId);
}
