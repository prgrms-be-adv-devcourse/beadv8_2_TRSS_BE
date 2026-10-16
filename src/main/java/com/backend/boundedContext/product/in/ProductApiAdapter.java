package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.dto.StockChangeDto;
import com.backend.shared.product.dto.StockRestoreReason;
import com.backend.shared.product.out.ProductApi;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * {@link ProductApi} 구현체. 규칙은 {@link ProductApi}의 설명을 따른다.
 */
@Component
@RequiredArgsConstructor
public class ProductApiAdapter implements ProductApi {

    @Lazy  // 컨텍스트 간 순환 참조 방지
    private final ProductFacade productFacade;

    @Override
    public List<OptionSnapshotDto> getOptions(Collection<Long> optionIds) {
        return productFacade.getOptions(optionIds);
    }

    @Override
    public void deductStock(List<StockChangeDto> items) {
        productFacade.deductStock(items);
    }

    @Override
    public void restoreStock(List<StockChangeDto> items, StockRestoreReason reason) {
        productFacade.restoreStock(items, reason);
    }

    @Override
    public void stopAllBySeller(Long sellerId, String reason) {
        productFacade.stopAllBySeller(sellerId, reason);
    }

    @Override
    public void resumeAllBySeller(Long sellerId) {
        productFacade.resumeAllBySeller(sellerId);
    }
}
