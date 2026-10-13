package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.AdminCategoryTreeDto;
import com.backend.boundedContext.product.app.dto.CategoryDto;
import com.backend.boundedContext.product.app.dto.CategoryTreeDto;
import com.backend.boundedContext.product.app.dto.ProductSampleDto;
import com.backend.boundedContext.product.domain.Category;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.dto.StockChangeDto;
import com.backend.shared.product.dto.StockRestoreReason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    /** 승인된 판매자의 상품을 등록하고 상품 ID를 돌려준다. */
    @Transactional
    public Long createProduct(Long memberId, Long categoryId, String name, String description,
                              long price, int stock) {
        Long sellerId = productSupport.getSellableSellerId(memberId);
        return productCreateProductUseCase.createProduct(sellerId, categoryId, name, description, price, stock).getId();
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
     * 초기 카테고리를 넣는다. 카테고리가 하나라도 있으면 아무것도 하지 않는다.
     * 대분류 이름 → 소분류 이름 목록이며, 넣은 순서가 정렬 순서가 된다.
     */
    @Transactional
    public void createBaseCategories(Map<String, List<String>> baseCategories) {
        if (productSupport.countCategories() > 0) return;

        int rootOrder = 0;
        for (Map.Entry<String, List<String>> entry : baseCategories.entrySet()) {
            Category root = productCreateCategoryUseCase.createRootCategory(entry.getKey(), rootOrder++);
            for (String childName : entry.getValue()) {
                productCreateCategoryUseCase.createCategory(childName, root.getId());   // 넣은 순서대로 맨 뒤에 붙음
            }
        }
    }

    /**
     * 시드 판매자의 샘플 상품을 등록하고 등록한 개수를 돌려준다.
     * 상품이 이미 있거나 승인된 판매자가 아니면 아무것도 하지 않고 0을 돌려준다.
     */
    @Transactional
    public int createSampleProducts(Long sellerMemberId, List<ProductSampleDto> samples) {
        if (productSupport.countProducts() > 0) return 0;

        Optional<Long> sellerId = productSupport.findSellableSellerId(sellerMemberId);
        if (sellerId.isEmpty()) return 0;

        for (ProductSampleDto sample : samples) {
            Category category = productSupport.getChildCategory(sample.parentCategoryName(), sample.categoryName());
            productCreateProductUseCase.createProduct(sellerId.get(), category.getId(), sample.name(),
                    sample.name() + " 샘플 상품입니다.", sample.price(), sample.stock());
        }
        return samples.size();
    }
}
