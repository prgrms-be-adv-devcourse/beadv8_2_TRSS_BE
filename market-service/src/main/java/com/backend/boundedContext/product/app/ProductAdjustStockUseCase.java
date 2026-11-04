package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductErrorCode;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.StockHistory;
import com.backend.boundedContext.product.out.StockHistoryRepository;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductAdjustStockUseCase {
    private final ProductSupport productSupport;
    private final StockHistoryRepository stockHistoryRepository;

    /**
     * 판매자가 옵션 재고를 조정하고 ADJUST 이력을 남긴다.
     * 주문 차감과 같은 순서(상품 → 옵션)로 잠근 뒤 최신 재고로 계산한다.
     * 다른 판매자의 상품이거나 다른 상품의 옵션이면 있는지 드러내지 않도록 404로 답한다.
     */
    public ProductOption adjustStock(Long sellerId, Long productId, Long optionId, int changeQty) {
        ProductOption option = productSupport.lockOptions(List.of(optionId)).get(optionId);   // 없으면 404

        Product product = option.getProduct();
        if (!product.getId().equals(productId) || !product.getSellerId().equals(sellerId)) {
            throw new DomainException(ProductErrorCode.PRODUCT_NOT_FOUND);
        }

        option.adjustStock(changeQty);   // 중지·삭제 상품 409, 삭제 옵션 409, 0 미만 400
        stockHistoryRepository.save(StockHistory.ofAdjust(option, changeQty));
        return option;
    }
}
