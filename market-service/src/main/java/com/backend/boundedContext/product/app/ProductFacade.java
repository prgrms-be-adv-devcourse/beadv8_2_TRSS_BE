package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.AdminCategoryTreeDto;
import com.backend.boundedContext.product.app.dto.PopularKeywordDto;
import com.backend.boundedContext.product.app.dto.AdminProductStopRequestDto;
import com.backend.boundedContext.product.app.dto.AdminProductSummaryDto;
import com.backend.boundedContext.product.domain.AdminProductDisplayStatus;
import com.backend.boundedContext.product.domain.ProductStopRequestStatus;
import com.backend.boundedContext.product.app.dto.CategoryDto;
import com.backend.boundedContext.product.app.dto.CategoryTreeDto;
import com.backend.boundedContext.product.app.dto.OptionStockDto;
import com.backend.boundedContext.product.app.dto.ProductDetailDto;
import com.backend.boundedContext.product.app.dto.ProductImageFile;
import com.backend.boundedContext.product.app.dto.ProductOptionSpec;
import com.backend.boundedContext.product.app.dto.ProductStopRequestDto;
import com.backend.boundedContext.product.app.dto.MemberRecommendationsDto;
import com.backend.boundedContext.product.app.dto.ProductSummaryDto;
import com.backend.boundedContext.product.app.dto.StockHistoryDto;
import com.backend.boundedContext.product.app.dto.SellerProductFormDto;
import com.backend.boundedContext.product.app.dto.SellerProductCountsDto;
import com.backend.boundedContext.product.app.dto.SellerProductSummaryDto;
import com.backend.boundedContext.product.app.dto.WishlistItemDto;
import com.backend.boundedContext.product.app.wishlist.ProductAddWishlistUseCase;
import com.backend.boundedContext.product.app.wishlist.ProductGetWishlistUseCase;
import com.backend.boundedContext.product.app.wishlist.ProductRemoveWishlistUseCase;
import com.backend.boundedContext.product.domain.ProductDisplayStatus;
import com.backend.boundedContext.product.domain.ProductSort;
import com.backend.global.page.PageResponse;
import org.springframework.data.domain.Pageable;
import com.backend.boundedContext.product.app.dto.ProductSampleDto;
import com.backend.boundedContext.product.domain.Category;
import com.backend.boundedContext.product.domain.OptionStock;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.dto.ProductSnapshotDto;
import com.backend.shared.product.dto.StockChangeDto;
import com.backend.shared.product.dto.StockRestoreReason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.OptionalInt;
import java.util.Optional;
import java.util.Set;


@Service
@RequiredArgsConstructor
public class ProductFacade {
    private final ProductSupport productSupport;
    private final ProductCreateCategoryUseCase productCreateCategoryUseCase;
    private final ProductModifyCategoryUseCase productModifyCategoryUseCase;
    private final ProductCreateProductUseCase productCreateProductUseCase;
    private final ProductDeductStockUseCase productDeductStockUseCase;
    private final ProductRestoreStockUseCase productRestoreStockUseCase;
    private final ProductStopAllBySellerUseCase productStopAllBySellerUseCase;
    private final ProductResumeAllBySellerUseCase productResumeAllBySellerUseCase;
    private final ProductDeleteProductUseCase productDeleteProductUseCase;
    private final ProductRequestStopUseCase productRequestStopUseCase;
    private final ProductGetStockHistoriesUseCase productGetStockHistoriesUseCase;
    private final ProductAdjustStockUseCase productAdjustStockUseCase;
    private final ProductAddOptionUseCase productAddOptionUseCase;
    private final ProductModifyOptionUseCase productModifyOptionUseCase;
    private final ProductDeleteOptionUseCase productDeleteOptionUseCase;
    private final ProductCreateDiscountUseCase productCreateDiscountUseCase;
    private final ProductModifyDiscountUseCase productModifyDiscountUseCase;
    private final ProductDeleteDiscountUseCase productDeleteDiscountUseCase;
    private final ProductEndDiscountUseCase productEndDiscountUseCase;
    private final ProductSearchProductsUseCase productSearchProductsUseCase;
    private final ProductGetPopularKeywordsUseCase productGetPopularKeywordsUseCase;
    private final ProductGetProductDetailUseCase productGetProductDetailUseCase;
    private final ProductGetSimilarProductsUseCase productGetSimilarProductsUseCase;
    private final ProductGetMemberRecommendationsUseCase productGetMemberRecommendationsUseCase;
    private final ProductGetRecentRecommendationsUseCase productGetRecentRecommendationsUseCase;
    private final ProductRecommendSupport productRecommendSupport;
    private final ProductGetSellerProductsUseCase productGetSellerProductsUseCase;
    private final ProductModifyProductUseCase productModifyProductUseCase;
    private final ProductGetAdminProductsUseCase productGetAdminProductsUseCase;
    private final ProductStopByAdminUseCase productStopByAdminUseCase;
    private final ProductGetStopRequestsUseCase productGetStopRequestsUseCase;
    private final ProductApproveStopRequestUseCase productApproveStopRequestUseCase;
    private final ProductRejectStopRequestUseCase productRejectStopRequestUseCase;
    private final ProductAddWishlistUseCase productAddWishlistUseCase;
    private final ProductRemoveWishlistUseCase productRemoveWishlistUseCase;
    private final ProductGetWishlistUseCase productGetWishlistUseCase;
    private final ProductSearchIndexSupport productSearchIndexSupport;
    private final ProductChangeEventSupport productChangeEventSupport;
    private final ProductBehaviorSupport productBehaviorSupport;

    // 내부 API (ProductApiAdapter). 호출한 쪽 트랜잭션에 참여한다

    @Transactional(readOnly = true)
    public List<OptionSnapshotDto> getOptions(Collection<Long> optionIds) {
        return productSupport.getOptionSnapshots(optionIds);
    }

    // 상품 여러 개의 현재 정보 (ProductApi, 쿠폰 발행과 협약 pick 상품 목록)
    @Transactional(readOnly = true)
    public List<ProductSnapshotDto> getProducts(Collection<Long> productIds) {
        return productSupport.getProductSnapshots(productIds);
    }

    // 재고 차감·복원은 주문 상태 변경과 한 트랜잭션이어야 한다. 트랜잭션 밖에서 부르면 IllegalTransactionStateException
    @Transactional(propagation = Propagation.MANDATORY)
    public void deductStock(List<StockChangeDto> items) {
        productChanged(productDeductStockUseCase.deductStock(items));   // 품절 여부가 바뀐 상품만
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void restoreStock(List<StockChangeDto> items, StockRestoreReason reason) {
        productChanged(productRestoreStockUseCase.restoreStock(items, reason));   // 품절 여부가 바뀐 상품만
    }

    /** 호출한 쪽 트랜잭션이 끝날 때까지 잠금을 유지해야 하므로 트랜잭션 없이 부르면 예외가 난다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockProductsForOrderChange(Collection<Long> productIds) {
        productSupport.lockProductsForShare(productIds);
    }

    @Transactional
    public void stopAllBySeller(Long sellerId, String reason) {
        productChanged(productStopAllBySellerUseCase.stopAllBySeller(sellerId, reason));
    }

    @Transactional
    public void resumeAllBySeller(Long sellerId) {
        productChanged(productResumeAllBySellerUseCase.resumeAllBySeller(sellerId));
    }

    /** 상품 검색. 비슷한 상품과 같이 트랜잭션을 잡지 않는다 (ES 검색·상호명 조회를 트랜잭션 밖에서 하려고 유스케이스가 나눠 잡는다) */
    public PageResponse<ProductSummaryDto> searchProducts(String keyword, Long categoryId, ProductSort sort,
                                                          boolean excludeSoldOut, boolean discountedOnly, Pageable pageable) {
        return productSearchProductsUseCase.searchProducts(keyword, categoryId, sort, excludeSoldOut, discountedOnly, pageable);
    }

    /** 최근 24시간 인기 검색어와 한 시간 전 대비 순위 변동 (DB를 읽지 않는다) */
    public List<PopularKeywordDto> getPopularKeywords() {
        return productGetPopularKeywordsUseCase.getPopularKeywords(LocalDateTime.now());
    }

    /** 상품 상세의 비슷한 상품 (속성 + 행동 점수). ES 조회를 트랜잭션 밖에서 하려고 트랜잭션을 잡지 않는다 (유스케이스가 나눠 잡는다) */
    public List<ProductSummaryDto> getSimilarProducts(Long productId, int size) {
        return productGetSimilarProductsUseCase.getSimilarProducts(productId, size);
    }

    /** 회원 추천 (회원님을 위한 추천 + 기준 상품별 섹션). 로그인하지 않았으면 인기순. 트랜잭션은 유스케이스가 나눠 잡는다 */
    public MemberRecommendationsDto getMemberRecommendations(Long memberId, int size) {
        return productGetMemberRecommendationsUseCase.getRecommendations(memberId, size);
    }

    /** 최근 본 상품 기반 추천. 트랜잭션은 유스케이스가 나눠 잡는다 */
    public List<ProductSummaryDto> getRecentRecommendations(List<Long> recentViewIds, int size) {
        return productGetRecentRecommendationsUseCase.getRecommendations(recentViewIds, size);
    }

    /** 회원 추천을 다시 계산해 저장한다 (찜·주문·리뷰 이벤트를 받은 컨슈머) */
    public void refreshMemberRecommendations(Long memberId) {
        productRecommendSupport.compute(memberId);
    }

    /** 로그인하지 않은 구매자의 상품 상세 (찜 여부는 false) */
    @Transactional(readOnly = true)
    public ProductDetailDto getProductDetail(Long productId) {
        return productGetProductDetailUseCase.getProductDetail(productId, null);
    }

    /** 구매자 상품 상세. memberId가 null이 아니면 그 회원의 찜 여부를 채운다 */
    @Transactional(readOnly = true)
    public ProductDetailDto getProductDetail(Long productId, Long memberId) {
        return productGetProductDetailUseCase.getProductDetail(productId, memberId);
    }

    /** 상품을 찜한다. 이미 찜했으면 그대로 성공 */
    @Transactional
    public void addWishlist(Long memberId, Long productId) {
        productAddWishlistUseCase.addWishlist(memberId, productId);
    }

    /** 찜을 해제한다. 찜하지 않았으면 그대로 성공 */
    @Transactional
    public void removeWishlist(Long memberId, Long productId) {
        productRemoveWishlistUseCase.removeWishlist(memberId, productId);
    }

    /** 내 찜 목록 (최근에 찜한 순) */
    @Transactional(readOnly = true)
    public PageResponse<WishlistItemDto> getWishlist(Long memberId, Pageable pageable) {
        return productGetWishlistUseCase.getWishlist(memberId, pageable);
    }

    /** 승인된 판매자의 옵션 없는 상품을 전성분 없이 등록하고 상품 ID를 돌려준다. */
    @Transactional
    public Long createProduct(Long memberId, Long categoryId, String name, String description,
                              long price, int stock, ProductImageFile thumbnail, List<ProductImageFile> detailImages) {
        Long sellerId = productSupport.getSellableSellerId(memberId);
        Long productId = productCreateProductUseCase.createProduct(sellerId, categoryId, name, description, price, stock,
                thumbnail, detailImages).getId();
        productChanged(Set.of(productId));
        return productId;
    }

    /**
     * 승인된 판매자의 상품을 옵션 조합과 함께 등록하고 상품 ID를 돌려준다. 대표 이미지는 필수, 상세 이미지는 선택이다.
     * 뷰티 소분류면 전성분이 필수다.
     */
    @Transactional
    public Long createProduct(Long memberId, Long categoryId, String name, String description, long price,
                              List<String> optionNames, List<ProductOptionSpec> options,
                              ProductImageFile thumbnail, List<ProductImageFile> detailImages, String ingredientsText) {
        Long sellerId = productSupport.getSellableSellerId(memberId);
        Long productId = productCreateProductUseCase.createProduct(sellerId, categoryId, name, description, price,
                optionNames, options, thumbnail, detailImages, ingredientsText).getId();
        productChanged(Set.of(productId));
        return productId;
    }

    /** 승인된 판매자의 본인 상품 목록 */
    @Transactional(readOnly = true)
    public PageResponse<SellerProductSummaryDto> getSellerProducts(Long memberId, ProductDisplayStatus status,
                                                                   String keyword, Pageable pageable) {
        Long sellerId = productSupport.getSellableSellerId(memberId);
        return productGetSellerProductsUseCase.getSellerProducts(sellerId, status, keyword, pageable);
    }

    /** 승인된 판매자의 본인 상품 탭별 개수 */
    @Transactional(readOnly = true)
    public SellerProductCountsDto getSellerProductCounts(Long memberId, String keyword) {
        Long sellerId = productSupport.getSellableSellerId(memberId);
        return productGetSellerProductsUseCase.getSellerProductCounts(sellerId, keyword);
    }

    /** 승인된 판매자의 본인 상품 수정 폼 값 */
    @Transactional(readOnly = true)
    public SellerProductFormDto getSellerProductForm(Long memberId, Long productId) {
        Long sellerId = productSupport.getSellableSellerId(memberId);
        return productSupport.getSellerProductForm(sellerId, productId);
    }

    /**
     * 승인된 판매자가 본인 상품 정보와 이미지를 수정한다. null인 항목은 그대로 둔다.
     * keepDetailImageIds: 남길 상세 이미지(순서대로, null이면 그대로), thumbnail: 교체할 대표 이미지, newDetailImages: 뒤에 붙일 상세 이미지
     */
    @Transactional
    public void modifyProduct(Long memberId, Long productId, String name, Long price, Long categoryId,
                              String description, List<Long> keepDetailImageIds, ProductImageFile thumbnail,
                              List<ProductImageFile> newDetailImages, String ingredientsText) {
        Long sellerId = productSupport.getSellableSellerId(memberId);
        productModifyProductUseCase.modifyProduct(sellerId, productId, name, price, categoryId, description,
                keepDetailImageIds, thumbnail, newDetailImages, ingredientsText);
        productChanged(Set.of(productId));
    }

    /** 승인된 판매자가 본인 상품을 지운다 (소프트 삭제). 결제 대기 주문 취소와 심사 중인 중지 요청 정리를 함께 한다. */
    @Transactional
    public void deleteProduct(Long memberId, Long productId) {
        Long sellerId = productSupport.getSellableSellerId(memberId);
        productDeleteProductUseCase.deleteProduct(sellerId, productId);
        productChanged(Set.of(productId));
    }

    /** 승인된 판매자가 본인 상품의 판매 중지를 요청한다. 관리자가 승인해야 중지된다. */
    @Transactional
    public ProductStopRequestDto requestStop(Long memberId, Long productId, String reason) {
        Long sellerId = productSupport.getSellableSellerId(memberId);
        return ProductStopRequestDto.from(productRequestStopUseCase.requestStop(sellerId, productId, reason));
    }

    /** 승인된 판매자의 본인 상품 옵션 재고 이력 (최신순) */
    @Transactional(readOnly = true)
    public PageResponse<StockHistoryDto> getStockHistories(Long memberId, Long productId, Long optionId, Pageable pageable) {
        Long sellerId = productSupport.getSellableSellerId(memberId);
        return productGetStockHistoriesUseCase.getStockHistories(sellerId, productId, optionId, pageable);
    }

    /** 승인된 판매자가 본인 상품에 옵션 조합을 하나 더하고, 만든 옵션 ID를 돌려준다. */
    @Transactional
    public Long addOption(Long memberId, Long productId, ProductOptionSpec spec) {
        Long sellerId = productSupport.getSellableSellerId(memberId);
        Long optionId = productAddOptionUseCase.addOption(sellerId, productId, spec).getId();
        productChanged(Set.of(productId));   // 재고 있는 옵션이 생기면 품절이 풀린다
        return optionId;
    }

    /** 승인된 판매자가 본인 상품 옵션의 추가금을 바꾼다. */
    @Transactional
    public void modifyOption(Long memberId, Long productId, Long optionId, long additionalPrice) {
        Long sellerId = productSupport.getSellableSellerId(memberId);
        productModifyOptionUseCase.modifyOption(sellerId, productId, optionId, additionalPrice);
    }

    /** 승인된 판매자가 본인 상품의 옵션 조합을 지운다 (소프트 삭제). 마지막 옵션은 지울 수 없다. */
    @Transactional
    public void deleteOption(Long memberId, Long productId, Long optionId) {
        Long sellerId = productSupport.getSellableSellerId(memberId);
        productDeleteOptionUseCase.deleteOption(sellerId, productId, optionId);
        productChanged(Set.of(productId));   // 재고 있는 옵션을 지우면 품절이 될 수 있다
    }

    /** 승인된 판매자가 본인 상품에 기간 할인을 걸고, 만든 할인 ID를 돌려준다. */
    @Transactional
    public Long createDiscount(Long memberId, Long productId, long salePrice, LocalDateTime startsAt, LocalDateTime endsAt) {
        Long sellerId = productSupport.getSellableSellerId(memberId);
        Long discountId = productCreateDiscountUseCase.createDiscount(sellerId, productId, salePrice, startsAt, endsAt).getId();
        productChanged(Set.of(productId));
        return discountId;
    }

    /** 승인된 판매자가 본인 상품의 할인을 부분 수정한다 (null인 값은 그대로, 진행 중이면 끝 시각만). */
    @Transactional
    public void modifyDiscount(Long memberId, Long productId, Long discountId, Long salePrice,
                               LocalDateTime startsAt, LocalDateTime endsAt) {
        Long sellerId = productSupport.getSellableSellerId(memberId);
        productModifyDiscountUseCase.modifyDiscount(sellerId, productId, discountId, salePrice, startsAt, endsAt);
        productChanged(Set.of(productId));
    }

    /** 승인된 판매자가 본인 상품의 시작 전인 할인을 지운다. */
    @Transactional
    public void deleteDiscount(Long memberId, Long productId, Long discountId) {
        Long sellerId = productSupport.getSellableSellerId(memberId);
        productDeleteDiscountUseCase.deleteDiscount(sellerId, productId, discountId);
        productChanged(Set.of(productId));
    }

    /** 승인된 판매자가 본인 상품의 진행 중인 할인을 지금 끝낸다. */
    @Transactional
    public void endDiscount(Long memberId, Long productId, Long discountId) {
        Long sellerId = productSupport.getSellableSellerId(memberId);
        productEndDiscountUseCase.endDiscount(sellerId, productId, discountId);
        productChanged(Set.of(productId));
    }

    /** 승인된 판매자가 자기 상품 옵션의 재고를 증감량만큼 조정하고, 조정 후 재고를 돌려준다. */
    @Transactional
    public OptionStockDto adjustStock(Long memberId, Long productId, Long optionId, int changeQty) {
        Long sellerId = productSupport.getSellableSellerId(memberId);
        OptionStock stock = productAdjustStockUseCase.adjustStock(sellerId, productId, optionId, changeQty);
        boolean wasSoldOut = stock.getStock() - changeQty == 0;
        if (wasSoldOut != stock.isSoldOut()) {
            productChanged(Set.of(productId));
        }
        return OptionStockDto.from(stock);
    }

    // 관리자 상품 관리

    /** 관리자 상품 목록 (삭제된 상품 포함, 최신순). null인 조건은 적용하지 않는다 */
    @Transactional(readOnly = true)
    public PageResponse<AdminProductSummaryDto> getProductsForAdmin(Long sellerId, AdminProductDisplayStatus status, Long categoryId,
                                                                    String keyword, Pageable pageable) {
        return productGetAdminProductsUseCase.getProducts(sellerId, status, categoryId, keyword, pageable);
    }

    /** 관리자가 상품 판매를 바로 멈춘다. 결제 대기 주문 취소와 심사 중인 중지 요청 정리를 함께 한다 */
    @Transactional
    public void stopProductByAdmin(Long productId, String reason) {
        productStopByAdminUseCase.stopProduct(productId, reason);
        productChanged(Set.of(productId));
    }

    /** 관리자 판매 중지 요청 목록 (오래된 순) */
    @Transactional(readOnly = true)
    public PageResponse<AdminProductStopRequestDto> getStopRequestsForAdmin(ProductStopRequestStatus status, Pageable pageable) {
        return productGetStopRequestsUseCase.getStopRequests(status, pageable);
    }

    /** 판매 중지 요청을 승인해 상품을 판매 중지한다 */
    @Transactional
    public AdminProductStopRequestDto approveStopRequest(Long requestId) {
        AdminProductStopRequestDto approved = AdminProductStopRequestDto.from(productApproveStopRequestUseCase.approve(requestId));
        productChanged(Set.of(approved.productId()));
        return approved;
    }

    /** 판매 중지 요청을 반려한다. 상품은 그대로 판매 중이다 */
    @Transactional
    public AdminProductStopRequestDto rejectStopRequest(Long requestId, String rejectReason) {
        return AdminProductStopRequestDto.from(productRejectStopRequestUseCase.reject(requestId, rejectReason));
    }

    @Transactional(readOnly = true)
    public List<CategoryTreeDto> getCategoryTree() {
        return productSupport.getCategoryTree();
    }

    @Transactional(readOnly = true)
    public List<AdminCategoryTreeDto> getCategoryTreeForAdmin() {
        return productSupport.getCategoryTreeForAdmin();
    }

    @Transactional
    public Long createCategory(String name, Long parentId) {
        return productCreateCategoryUseCase.createCategory(name, parentId).getId();
    }

    @Transactional
    public CategoryDto modifyCategory(Long categoryId, String name, Boolean active) {
        CategoryDto before = CategoryDto.from(productSupport.getCategory(categoryId));   // 수정하면 같은 엔티티가 바뀌므로 값을 먼저 담는다
        CategoryDto category = CategoryDto.from(productModifyCategoryUseCase.modifyCategory(categoryId, name, active));
        if (!before.name().equals(category.name()) || before.active() != category.active()) {
            // 상품 변경 이벤트의 소분류 이름·판매 여부(onSale = 판매 중이고 카테고리 사용 중)가 바뀌었을 때만 그 소분류의 상품을 다시 알린다
            productChanged(productSupport.getNotDeletedProductIdsInCategory(categoryId));
        }
        return category;
    }

    /**
     * 시드 판매자의 샘플 상품을 등록하고 등록한 개수를 돌려준다. 대표 이미지는 모두 sampleThumbnail을 쓴다(상품마다 파일은 따로 저장).
     * 상품이 이미 있거나 승인된 판매자가 아니면 아무것도 하지 않고 0을 돌려준다.
     */
    @Transactional
    public int createSampleProducts(Long sellerMemberId, List<ProductSampleDto> samples, ProductImageFile sampleThumbnail) {
        if (productSupport.countProducts() > 0) return 0;

        Optional<Long> sellerId = productSupport.findSellableSellerId(sellerMemberId);
        if (sellerId.isEmpty()) return 0;

        List<Long> productIds = new ArrayList<>();
        for (ProductSampleDto sample : samples) {
            Category category = productSupport.getChildCategory(sample.parentCategoryName(), sample.categoryName());
            productIds.add(productCreateProductUseCase.createProduct(sellerId.get(), category.getId(), sample.name(),
                    sample.name() + " 샘플 상품입니다.", sample.price(), sample.stock(), sampleThumbnail, List.of()).getId());
        }
        productChanged(productIds);
        return samples.size();
    }

    /**
     * 상품의 내용·판매 상태·품절 여부·옵션·할인·카테고리가 바뀌었을 때 부른다.
     * 같은 트랜잭션에서 상품 변경 이벤트를 outbox에 저장한다. AI 추천과 검색 색인 컨슈머가 Kafka로 받는다.
     */
    private void productChanged(Collection<Long> productIds) {
        productChangeEventSupport.publishChanged(productIds);
    }

    /** 판매 중인 상품 전체의 변경 이벤트를 다시 발행한다. AI 서비스를 처음 띄울 때 관리자가 부른다 */
    @Transactional
    public int publishAllProductChanges() {
        return productChangeEventSupport.publishAllOnSale();
    }

    // 상품 검색 색인 (ES). 변경한 트랜잭션이 커밋된 뒤에 부른다

    /** 판매자의 판매 중 상품 ID (상호가 바뀌어 검색 색인을 다시 할 상품, 검색 색인 컨슈머) */
    @Transactional(readOnly = true)
    public List<Long> getOnSaleProductIdsBySeller(Long sellerId) {
        return productSupport.getOnSaleProductIdsBySeller(sellerId);
    }

    /** 상품들을 DB의 지금 값으로 다시 색인한다 (검색 색인 컨슈머). 새 트랜잭션에서 읽는다 */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public void indexProducts(Collection<Long> productIds) {
        productSearchIndexSupport.indexProducts(productIds);
    }

    /**
     * 지난 성공 실행 뒤로 할인이 시작했거나 끝난 상품의 상품 변경 이벤트를 발행하고, 그 상품 수를 돌려준다 (할인 경계 스케줄러).
     * 직접 색인하지 않고 이벤트만 남기므로 색인은 검색 색인 컨슈머 한 곳에서만 한다. outbox 저장에 쓰기 트랜잭션이 필요하다.
     * lastSucceededAt이 null이면(기동 후 첫 실행) 정해 둔 구간만큼 돌아본다
     */
    @Transactional
    public int publishDiscountBoundaryChanges(LocalDateTime lastSucceededAt, LocalDateTime now) {
        List<Long> productIds = productSearchIndexSupport.findDiscountBoundaryProductIds(lastSucceededAt, now);
        productChanged(productIds);
        return productIds.size();
    }

    /**
     * (from, now]에 판매량이 바뀌었을 수 있는 상품(결제·취소·반품, 판매량 집계 구간 밖으로 나간 판매)의 지표 변경 이벤트를 발행하고,
     * 그 상품 수를 돌려준다 (판매 지표 스케줄러). 색인은 검색 색인 컨슈머가 한다. outbox 저장에 쓰기 트랜잭션이 필요하다
     */
    @Transactional
    public int publishSalesMetricsChanges(LocalDateTime from, LocalDateTime now) {
        List<Long> productIds = productBehaviorSupport.findProductIdsWithSalesChanged(from, now);
        productChangeEventSupport.publishMetricsChanged(productIds);
        return productIds.size();
    }

    /** 상호명 없이 색인된 상품을 다시 색인해 상호명을 채운다. 채운 상품 수 (복구 스케줄러) */
    @Transactional(readOnly = true)
    public int fillMissingBusinessNames() {
        return productSearchIndexSupport.fillMissingBusinessNames();
    }

    /** 판매 중인 상품 전체를 새 인덱스에 색인하고 검색 alias를 옮긴다. 색인한 상품 수를 돌려준다 (관리자) */
    @Transactional(readOnly = true)
    public int rebuildSearchIndex() {
        return productSearchIndexSupport.rebuildIndex();
    }

    /** 검색 인덱스가 최신이 아니면(alias 없음·매핑 버전 낮음) 전체 재색인하고 색인한 상품 수를, 아니면 빈 값을 돌려준다 (복구 스케줄러) */
    @Transactional(readOnly = true)
    public OptionalInt rebuildSearchIndexIfOutdated() {
        return productSearchIndexSupport.rebuildIndexIfOutdated();
    }
}
