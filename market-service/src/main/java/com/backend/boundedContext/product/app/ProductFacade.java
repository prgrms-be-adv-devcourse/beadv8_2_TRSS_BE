package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.AdminCategoryTreeDto;
import com.backend.boundedContext.product.app.dto.AdminProductStopRequestDto;
import com.backend.boundedContext.product.app.dto.AdminProductSummaryDto;
import com.backend.boundedContext.product.domain.AdminProductDisplayStatus;
import com.backend.boundedContext.product.domain.ProductStopRequestStatus;
import com.backend.boundedContext.product.app.dto.CategoryDto;
import com.backend.boundedContext.product.app.dto.CategoryTreeDto;
import com.backend.boundedContext.product.app.dto.OptionStockDto;
import com.backend.boundedContext.product.app.dto.ProductDetailDto;
import com.backend.boundedContext.product.app.dto.ProductImageFile;
import com.backend.boundedContext.product.app.dto.ProductStopRequestDto;
import com.backend.boundedContext.product.app.dto.ProductSummaryDto;
import com.backend.boundedContext.product.app.dto.StockHistoryDto;
import com.backend.boundedContext.product.app.dto.SellerProductFormDto;
import com.backend.boundedContext.product.app.dto.SellerProductCountsDto;
import com.backend.boundedContext.product.app.dto.SellerProductSummaryDto;
import com.backend.boundedContext.product.domain.ProductDisplayStatus;
import com.backend.boundedContext.product.domain.ProductSort;
import com.backend.global.page.PageResponse;
import org.springframework.data.domain.Pageable;
import com.backend.boundedContext.product.app.dto.ProductSampleDto;
import com.backend.boundedContext.product.domain.Category;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.dto.StockChangeDto;
import com.backend.shared.product.dto.StockRestoreReason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;


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
    private final ProductSearchProductsUseCase productSearchProductsUseCase;
    private final ProductGetProductDetailUseCase productGetProductDetailUseCase;
    private final ProductGetSellerProductsUseCase productGetSellerProductsUseCase;
    private final ProductModifyProductUseCase productModifyProductUseCase;
    private final ProductGetAdminProductsUseCase productGetAdminProductsUseCase;
    private final ProductStopByAdminUseCase productStopByAdminUseCase;
    private final ProductGetStopRequestsUseCase productGetStopRequestsUseCase;
    private final ProductApproveStopRequestUseCase productApproveStopRequestUseCase;
    private final ProductRejectStopRequestUseCase productRejectStopRequestUseCase;

    // 내부 API (ProductApiAdapter). 호출한 쪽 트랜잭션에 참여한다

    @Transactional(readOnly = true)
    public List<OptionSnapshotDto> getOptions(Collection<Long> optionIds) {
        return productSupport.getOptionSnapshots(optionIds);
    }

    @Transactional
    public void deductStock(List<StockChangeDto> items) {
        productDeductStockUseCase.deductStock(items);
    }

    @Transactional
    public void restoreStock(List<StockChangeDto> items, StockRestoreReason reason) {
        productRestoreStockUseCase.restoreStock(items, reason);
    }

    /** 호출한 쪽 트랜잭션이 끝날 때까지 잠금을 유지해야 하므로 트랜잭션 없이 부르면 예외가 난다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockProductsForOrderChange(Collection<Long> productIds) {
        productSupport.lockProductsForShare(productIds);
    }

    @Transactional
    public void stopAllBySeller(Long sellerId, String reason) {
        productStopAllBySellerUseCase.stopAllBySeller(sellerId, reason);
    }

    @Transactional
    public void resumeAllBySeller(Long sellerId) {
        productResumeAllBySellerUseCase.resumeAllBySeller(sellerId);
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductSummaryDto> searchProducts(String keyword, Long categoryId, ProductSort sort,
                                                          boolean excludeSoldOut, Pageable pageable) {
        return productSearchProductsUseCase.searchProducts(keyword, categoryId, sort, excludeSoldOut, pageable);
    }

    @Transactional(readOnly = true)
    public ProductDetailDto getProductDetail(Long productId) {
        return productGetProductDetailUseCase.getProductDetail(productId);
    }

    /** 승인된 판매자의 상품을 등록하고 상품 ID를 돌려준다. 대표 이미지는 필수, 상세 이미지는 선택이다. */
    @Transactional
    public Long createProduct(Long memberId, Long categoryId, String name, String description,
                              long price, int stock, ProductImageFile thumbnail, List<ProductImageFile> detailImages) {
        Long sellerId = productSupport.getSellableSellerId(memberId);
        return productCreateProductUseCase.createProduct(sellerId, categoryId, name, description, price, stock,
                thumbnail, detailImages).getId();
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
                              List<ProductImageFile> newDetailImages) {
        Long sellerId = productSupport.getSellableSellerId(memberId);
        productModifyProductUseCase.modifyProduct(sellerId, productId, name, price, categoryId, description,
                keepDetailImageIds, thumbnail, newDetailImages);
    }

    /** 승인된 판매자가 본인 상품을 지운다 (소프트 삭제). 결제 대기 주문 취소와 심사 중인 중지 요청 정리를 함께 한다. */
    @Transactional
    public void deleteProduct(Long memberId, Long productId) {
        Long sellerId = productSupport.getSellableSellerId(memberId);
        productDeleteProductUseCase.deleteProduct(sellerId, productId);
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

    /** 승인된 판매자가 자기 상품 옵션의 재고를 증감량만큼 조정하고, 조정 후 재고를 돌려준다. */
    @Transactional
    public OptionStockDto adjustStock(Long memberId, Long productId, Long optionId, int changeQty) {
        Long sellerId = productSupport.getSellableSellerId(memberId);
        return OptionStockDto.from(productAdjustStockUseCase.adjustStock(sellerId, productId, optionId, changeQty));
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
    }

    /** 관리자 판매 중지 요청 목록 (오래된 순) */
    @Transactional(readOnly = true)
    public PageResponse<AdminProductStopRequestDto> getStopRequestsForAdmin(ProductStopRequestStatus status, Pageable pageable) {
        return productGetStopRequestsUseCase.getStopRequests(status, pageable);
    }

    /** 판매 중지 요청을 승인해 상품을 판매 중지한다 */
    @Transactional
    public AdminProductStopRequestDto approveStopRequest(Long requestId) {
        return AdminProductStopRequestDto.from(productApproveStopRequestUseCase.approve(requestId));
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
        return CategoryDto.from(productModifyCategoryUseCase.modifyCategory(categoryId, name, active));
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

        for (ProductSampleDto sample : samples) {
            Category category = productSupport.getChildCategory(sample.parentCategoryName(), sample.categoryName());
            productCreateProductUseCase.createProduct(sellerId.get(), category.getId(), sample.name(),
                    sample.name() + " 샘플 상품입니다.", sample.price(), sample.stock(), sampleThumbnail, List.of());
        }
        return samples.size();
    }
}
