package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.StockHistoryDto;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.out.StockHistoryRepository;
import com.backend.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProductGetStockHistoriesUseCase {
    private static final Sort LATEST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final ProductSupport productSupport;
    private final StockHistoryRepository stockHistoryRepository;

    /**
     * 판매자 본인 상품 옵션의 재고 이력 (최신순). 판매 중지 상품도 볼 수 있다.
     * 다른 판매자·삭제된 상품이거나 다른 상품의 옵션이면 404.
     */
    public PageResponse<StockHistoryDto> getStockHistories(Long sellerId, Long productId, Long optionId,
                                                          Pageable pageable) {
        ProductOption option = productSupport.getSellerOption(sellerId, productId, optionId);
        Pageable latest = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), LATEST);

        return PageResponse.from(stockHistoryRepository.findByOptionId(option.getId(), latest), StockHistoryDto::from);
    }
}
