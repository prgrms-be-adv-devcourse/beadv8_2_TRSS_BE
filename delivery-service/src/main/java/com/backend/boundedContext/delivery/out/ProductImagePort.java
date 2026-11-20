package com.backend.boundedContext.delivery.out;

import java.util.Collection;
import java.util.Map;

/**
 * 상품 옵션의 현재 대표 이미지 조회 포트 (market-service 상품 내부 API).
 * 이미지는 배송 건에 저장하지 않고 물류 응답을 만들 때마다 붙인다.
 */
public interface ProductImagePort {

    /**
     * 옵션 ID별 현재 대표 이미지 URL. 없는 옵션·이미지가 없는 옵션은 결과에 없다.
     * 응답을 받지 못하면 502 EXTERNAL_SERVICE_ERROR(DomainException)를 던진다. 부르는 쪽이 잡아서 이미지 없이 응답한다.
     */
    Map<Long, String> getThumbnailUrls(Collection<Long> optionIds);
}
