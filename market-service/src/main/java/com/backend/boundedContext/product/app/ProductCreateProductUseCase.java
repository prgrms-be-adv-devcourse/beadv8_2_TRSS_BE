package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.ProductImageFile;
import com.backend.boundedContext.product.app.dto.ProductOptionSpec;
import com.backend.boundedContext.product.domain.Category;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.out.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductCreateProductUseCase {
    private final ProductSupport productSupport;
    private final ProductImageSupport productImageSupport;
    private final ProductRepository productRepository;

    /** 옵션이 없는 상품을 등록한다. 값이 없는 옵션 1개에 초기 재고를 넣는다. */
    public Product createProduct(Long sellerId, Long categoryId, String name, String description,
                                 long price, int stock, ProductImageFile thumbnail, List<ProductImageFile> detailImages) {
        return createProduct(sellerId, categoryId, name, description, price,
                List.of(), List.of(ProductOptionSpec.single(stock)), thumbnail, detailImages);
    }

    /**
     * 상품을 등록한다. 옵션 조합마다 옵션과 초기 재고를 만들고 재고 이력 INIT을 남긴다.
     * 옵션 이름이 없으면 값이 없는 조합 1개만 보낸다. 조합은 1~100개이고 같은 조합이 겹치면 안 된다.
     * 대표 이미지는 필수, 상세 이미지는 선택이다.
     */
    public Product createProduct(Long sellerId, Long categoryId, String name, String description, long price,
                                 List<String> optionNames, List<ProductOptionSpec> options,
                                 ProductImageFile thumbnail, List<ProductImageFile> detailImages) {
        Category category = productSupport.getCategory(categoryId);
        // 고를 수 없는 카테고리, 잘못된 옵션 이름이면 예외
        Product product = productRepository.save(Product.create(sellerId, category, name, description, price, optionNames));

        productSupport.checkOptionCombinations(options.stream()
                .map(spec -> ProductOption.normalizeValues(spec.values()))
                .toList());
        options.forEach(spec -> productSupport.addOption(product, spec));
        productImageSupport.addImages(product, thumbnail, detailImages);
        return product;
    }
}
