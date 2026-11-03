package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.ProductImageFile;
import com.backend.boundedContext.product.domain.Category;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.StockHistory;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.boundedContext.product.out.StockHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductCreateProductUseCase {
    private final ProductSupport productSupport;
    private final ProductImageSupport productImageSupport;
    private final ProductRepository productRepository;
    private final ProductOptionRepository productOptionRepository;
    private final StockHistoryRepository stockHistoryRepository;

    /**
     * 상품을 등록한다. 기본 옵션 1개를 만들어 초기 재고를 넣고, 재고 이력 INIT을 남긴다.
     * 대표 이미지는 필수, 상세 이미지는 선택이다.
     */
    public Product createProduct(Long sellerId, Long categoryId, String name, String description,
                                 long price, int stock, ProductImageFile thumbnail, List<ProductImageFile> detailImages) {
        Category category = productSupport.getCategory(categoryId);
        Product product = productRepository.save(Product.create(sellerId, category, name, description, price));   // 고를 수 없는 카테고리면 예외

        ProductOption option = productOptionRepository.save(ProductOption.createDefault(product, stock));
        stockHistoryRepository.save(StockHistory.ofInit(option));
        productImageSupport.addImages(product, thumbnail, detailImages);
        return product;
    }
}
