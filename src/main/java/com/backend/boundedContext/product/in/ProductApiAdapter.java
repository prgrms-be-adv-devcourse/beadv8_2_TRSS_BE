package com.backend.boundedContext.product.in;

import com.backend.shared.product.dto.ProductSnapshotDto;
import com.backend.shared.product.dto.ProductStatus;
import com.backend.shared.product.dto.StockChangeDto;
import com.backend.shared.product.dto.StockRestoreReason;
import com.backend.shared.product.out.ProductApi;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * {@link ProductApi} 구현체.
 *
 * <p>임시 구현(Stub)이다. 주문 컨텍스트가 먼저 개발할 수 있도록 고정값을 돌려주고,
 * 재고 차감·복원은 아무것도 하지 않는다. 실제 구현에서 ProductFacade 호출로 바꾼다.
 */
@Component
public class ProductApiAdapter implements ProductApi {

    private static final long STUB_SELLER_ID = 2L;  // DataInit의 2번 판매자
    private static final long STUB_PRICE = 10_000L;
    private static final int STUB_STOCK = 100;

    /** 요청한 ID마다 판매 중인 임시 상품을 돌려준다. */
    @Override
    public List<ProductSnapshotDto> getProducts(Collection<Long> productIds) {
        return productIds.stream()
                .distinct()
                .map(id -> new ProductSnapshotDto(
                        id, STUB_SELLER_ID, "임시 상품 " + id, STUB_PRICE, STUB_STOCK,
                        ProductStatus.ON_SALE, true, null, null))
                .toList();
    }

    @Override
    public void deductStock(List<StockChangeDto> items) {
        // 임시 구현: 아무것도 하지 않는다
    }

    @Override
    public void restoreStock(List<StockChangeDto> items, StockRestoreReason reason) {
        // 임시 구현: 아무것도 하지 않는다
    }
}
