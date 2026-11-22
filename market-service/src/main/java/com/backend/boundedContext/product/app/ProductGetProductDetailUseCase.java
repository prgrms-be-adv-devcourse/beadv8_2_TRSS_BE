package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.ProductImageSupport.DisplayImages;
import com.backend.boundedContext.product.app.dto.ProductDetailDto;
import com.backend.boundedContext.product.app.dto.ProductOptionDto;
import com.backend.boundedContext.product.domain.Category;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductPrice;
import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.out.wishlist.WishlistRepository;
import com.backend.shared.review.dto.ProductRatingStatsDto;
import com.backend.shared.review.out.ReviewApi;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;

/**
 * 구매자용 상품 상세. DB 값은 읽기 트랜잭션 하나에서 읽고, 상호명(회원 서비스 HTTP)은 그 트랜잭션이 끝난 뒤에 채운다.
 * 회원 서비스가 느려도 DB 커넥션을 잡고 기다리지 않고, 실패하면 상호명 없이 보여 준다.
 *
 * <p>쿼리: 상품(소분류·대분류 함께), 옵션 재고, 진행 중 할인, 리뷰 통계, 이미지(대표·상세 함께), 찜 수 (+ 로그인이면 찜 여부).
 * 찜·리뷰 통계·할인·재고는 방금 바뀐 값이 바로 보여야 해서 따로 읽는다.
 */
@Service
public class ProductGetProductDetailUseCase {
    private final ProductSupport productSupport;
    private final ProductImageSupport productImageSupport;
    private final ProductDiscountSupport productDiscountSupport;
    private final WishlistRepository wishlistRepository;
    private final ReviewApi reviewApi;
    private final TransactionTemplate readOnlyTransaction;

    public ProductGetProductDetailUseCase(ProductSupport productSupport, ProductImageSupport productImageSupport,
                                          ProductDiscountSupport productDiscountSupport,
                                          WishlistRepository wishlistRepository, ReviewApi reviewApi,
                                          PlatformTransactionManager transactionManager) {
        this.productSupport = productSupport;
        this.productImageSupport = productImageSupport;
        this.productDiscountSupport = productDiscountSupport;
        this.wishlistRepository = wishlistRepository;
        this.reviewApi = reviewApi;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    /**
     * 판매 중지·삭제된 상품이나 사용 중지된 카테고리의 상품은 404.
     * 가격은 조회 시각에 진행 중인 할인을 반영한다 (옵션 단가는 할인가 + 추가금).
     * memberId가 있으면(로그인) 그 회원의 찜 여부를 채우고, 없으면 false.
     */
    public ProductDetailDto getProductDetail(Long productId, Long memberId) {
        ProductDetailDto detail = readOnlyTransaction.execute(status -> readDetail(productId, memberId));
        String businessName = productSupport.getBusinessNamesOrEmpty(Set.of(detail.sellerId())).get(detail.sellerId());
        return detail.withBusinessName(businessName);
    }

    /** DB 값만으로 만든 상세 (상호명은 비어 있다) */
    private ProductDetailDto readDetail(Long productId, Long memberId) {
        Product product = productSupport.getDisplayableProduct(productId);   // 소분류·대분류를 함께 읽는다
        Category category = product.getCategory();
        Category parent = category.getParent();

        List<OptionStock> optionStocks = productSupport.getOptionStocksByProductId(Set.of(productId))
                .getOrDefault(productId, List.of());
        ProductPrice price = productDiscountSupport.getPrice(product);
        ProductRatingStatsDto rating = reviewApi.getRatingStats(List.of(productId))
                .getOrDefault(productId, ProductRatingStatsDto.empty(productId));
        DisplayImages images = productImageSupport.getDisplayImages(productId);

        return new ProductDetailDto(
                product.getId(), product.getName(), product.getDescription(), product.getPrice(),
                price.salePrice(), price.discountRate(), price.discountEndsAt(),
                parent.getId(), parent.getName(), category.getId(), category.getName(),
                product.getSellerId(), null, images.thumbnailUrl(), images.detailUrls(),
                ProductSupport.isSoldOut(optionStocks),
                product.getOptionNames(),
                optionStocks.stream().map(stock -> ProductOptionDto.from(stock, price)).toList(),
                memberId != null && wishlistRepository.existsByMemberIdAndProductId(memberId, productId),
                wishlistRepository.countByProductId(productId),
                rating.reviewCount(), rating.averageRating(),
                product.getIngredientsText());
    }
}
