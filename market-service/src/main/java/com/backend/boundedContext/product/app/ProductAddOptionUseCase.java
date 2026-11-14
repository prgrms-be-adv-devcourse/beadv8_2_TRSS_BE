package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.ProductOptionSpec;
import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class ProductAddOptionUseCase {
    private final ProductSupport productSupport;
    private final ProductOptionRepository productOptionRepository;

    /**
     * 판매자가 본인 상품에 옵션 조합을 하나 더한다. 초기 재고와 재고 이력 INIT을 함께 만든다.
     * 상품을 쓰기 잠금한 뒤 기존 조합을 보므로, 동시에 추가·삭제해도 개수 제한과 중복 검사가 어긋나지 않는다.
     * 다른 판매자·삭제된 상품은 404, 판매 중지 상품은 409, 값이 옵션 이름과 맞지 않으면 400,
     * 같은 조합이 있으면 409, 100개를 넘으면 400.
     */
    public OptionStock addOption(Long sellerId, Long productId, ProductOptionSpec spec) {
        Product product = productSupport.lockSellerProduct(sellerId, productId);
        product.checkEditable();

        List<List<String>> existing = productOptionRepository.findAllByProductIdAndDeletedAtIsNullOrderByIdAsc(productId)
                .stream().map(ProductOption::getValues).toList();
        productSupport.checkOptionCombinations(Stream.concat(
                existing.stream(), Stream.of(ProductOption.normalizeValues(spec.values()))).toList());

        return productSupport.addOption(product, spec);
    }
}
