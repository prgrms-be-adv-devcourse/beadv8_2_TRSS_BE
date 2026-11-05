package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.ProductDetailDto;
import com.backend.boundedContext.product.app.dto.ProductImageDto;
import com.backend.boundedContext.product.app.dto.ProductOptionDto;
import com.backend.boundedContext.product.domain.Category;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.out.wishlist.WishlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ProductGetProductDetailUseCase {
    private final ProductSupport productSupport;
    private final ProductImageSupport productImageSupport;
    private final WishlistRepository wishlistRepository;

    /**
     * 구매자용 상품 상세. 판매 중지·삭제된 상품이나 사용 중지된 카테고리의 상품은 404.
     * memberId가 있으면(로그인) 그 회원의 찜 여부를 채우고, 없으면 false.
     */
    public ProductDetailDto getProductDetail(Long productId, Long memberId) {
        Product product = productSupport.getDisplayableProduct(productId);
        Category category = product.getCategory();
        Category parent = category.getParent();

        List<ProductOption> options = productSupport.getOptionsByProductId(Set.of(productId))
                .getOrDefault(productId, List.of());
        String businessName = productSupport.getBusinessNames(Set.of(product.getSellerId()))
                .get(product.getSellerId());

        return new ProductDetailDto(
                product.getId(), product.getName(), product.getDescription(), product.getPrice(),
                parent.getId(), parent.getName(), category.getId(), category.getName(),
                product.getSellerId(), businessName, thumbnailUrl(productId),
                productImageSupport.getDetailImages(productId).stream().map(ProductImageDto::url).toList(),
                ProductSupport.isSoldOut(options),
                options.stream().map(ProductOptionDto::from).toList(),
                memberId != null && wishlistRepository.existsByMemberIdAndProductId(memberId, productId));
    }

    private String thumbnailUrl(Long productId) {
        ProductImageDto thumbnail = productImageSupport.getThumbnail(productId);
        return thumbnail == null ? null : thumbnail.url();
    }
}
