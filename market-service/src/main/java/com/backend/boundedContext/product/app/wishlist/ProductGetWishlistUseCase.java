package com.backend.boundedContext.product.app.wishlist;

import com.backend.boundedContext.product.app.ProductDiscountSupport;
import com.backend.boundedContext.product.app.ProductImageSupport;
import com.backend.boundedContext.product.app.ProductSupport;
import com.backend.boundedContext.product.app.dto.WishlistItemDto;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductPrice;
import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.boundedContext.product.domain.wishlist.Wishlist;
import com.backend.boundedContext.product.out.wishlist.WishlistRepository;
import com.backend.global.page.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductGetWishlistUseCase {
    private final ProductSupport productSupport;
    private final ProductImageSupport productImageSupport;
    private final ProductDiscountSupport productDiscountSupport;
    private final WishlistRepository wishlistRepository;

    /**
     * 내 찜 목록 (최근에 찜한 순). 판매 중지·삭제된 상품도 목록에 남기고 available = false로 준다.
     * 정렬은 요청 값과 관계없이 찜한 순서로 고정한다.
     */
    public PageResponse<WishlistItemDto> getWishlist(Long memberId, Pageable pageable) {
        Pageable latest = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("id")));
        Page<Wishlist> wishes = wishlistRepository.findPageByMemberId(memberId, latest);

        Set<Long> productIds = wishes.getContent().stream()
                .map(wish -> wish.getProduct().getId())
                .collect(Collectors.toSet());
        Map<Long, List<OptionStock>> stocks = productSupport.getOptionStocksByProductId(productIds);
        Map<Long, String> thumbnailUrls = productImageSupport.getThumbnailUrls(productIds);
        Map<Long, ProductPrice> prices = productDiscountSupport.getPrices(
                wishes.getContent().stream().map(Wishlist::getProduct).toList());

        return PageResponse.from(wishes.map(wish -> {
            Product product = wish.getProduct();
            ProductPrice price = prices.get(product.getId());
            return new WishlistItemDto(
                    product.getId(), product.getName(), product.getPrice(), price.salePrice(), price.discountRate(),
                    thumbnailUrls.get(product.getId()),
                    ProductSupport.isSoldOut(stocks.getOrDefault(product.getId(), List.of())),
                    product.isDisplayable(), wish.getCreatedAt());
        }));
    }
}
