package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.AdminCategoryTreeDto;
import com.backend.boundedContext.product.app.dto.CategoryTreeDto;
import com.backend.boundedContext.product.app.dto.SellerProductFormDto;
import com.backend.boundedContext.product.domain.Category;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductErrorCode;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import com.backend.boundedContext.product.domain.ProductStopRequest;
import com.backend.boundedContext.product.domain.StockChangeType;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
import com.backend.boundedContext.product.out.ProductStopRequestRepository;
import com.backend.boundedContext.product.out.StockHistoryRepository;
import com.backend.global.exception.DomainException;
import com.backend.shared.member.dto.SellerDto;
import com.backend.shared.member.out.MemberApi;
import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.dto.ProductUnsaleableReason;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
public class ProductSupport {
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final ProductOptionRepository productOptionRepository;
    private final StockHistoryRepository stockHistoryRepository;
    private final ProductStopRequestRepository productStopRequestRepository;
    private final MemberApi memberApi;
    private final ProductImageSupport productImageSupport;
    private final EntityManager entityManager;

    /**
     * 옵션 여러 개의 현재 정보. 삭제된 상품·옵션도 포함하고, 없는 ID만 빠진다.
     * 대표 이미지는 이미지 기능 전까지 null이다.
     */
    public List<OptionSnapshotDto> getOptionSnapshots(Collection<Long> optionIds) {
        if (optionIds.isEmpty()) return List.of();

        return productOptionRepository.findAllWithProductByIdIn(optionIds).stream()
                .map(option -> {
                    Product product = option.getProduct();
                    ProductUnsaleableReason reason = option.getUnsaleableReason();
                    return new OptionSnapshotDto(
                            option.getId(), product.getId(), product.getSellerId(), product.getName(), option.getName(),
                            product.getPrice(), option.getStock(), reason == null, reason, null);
                })
                .toList();
    }

    /**
     * 재고를 바꾸기 전에 상품(읽기)과 옵션(쓰기)을 ID 순으로 잠그고, 옵션 ID → 옵션으로 돌려준다.
     * 잠금 순서는 항상 상품 → 옵션이다. 없는 옵션이 있으면 404.
     *
     * <p>같은 트랜잭션에서 먼저 조회한 엔티티(예: 주문 생성 중 getOptions)는 잠금 쿼리를 다시 실행해도
     * 메모리의 옛 값을 그대로 돌려준다. 그래서 잠근 뒤 DB 값으로 다시 읽는다(refresh).
     * 잠금을 쥐고 있으므로 다시 읽은 값은 커밋 전까지 다른 트랜잭션이 바꾸지 못한다.
     */
    public Map<Long, ProductOption> lockOptions(Collection<Long> optionIds) {
        Set<Long> ids = new HashSet<>(optionIds);

        productRepository.findAllForShareByIdIn(productOptionRepository.findProductIdsByIdIn(ids))
                .forEach(entityManager::refresh);

        List<ProductOption> options = productOptionRepository.findAllForUpdateByIdIn(ids);
        options.forEach(entityManager::refresh);
        if (options.size() != ids.size()) {
            throw new DomainException(ProductErrorCode.PRODUCT_NOT_FOUND);
        }

        return options.stream().collect(Collectors.toMap(ProductOption::getId, Function.identity()));
    }

    /**
     * 판매자의 이 상태 상품을 ID 순으로 쓰기 잠금하고 DB 값으로 다시 읽는다 (판매자 전체 중지·재개용).
     * 같은 트랜잭션에서 먼저 읽어 둔 상품이 있어도 잠근 뒤의 최신 상태로 판단하기 위해 refresh한다 (lockOptions와 같은 이유).
     */
    public List<Product> lockSellerProducts(Long sellerId, ProductSaleStatus status) {
        List<Product> products = productRepository.findAllForUpdateBySellerIdAndStatus(sellerId, status);
        products.forEach(entityManager::refresh);
        return products;
    }

    /** 주문 항목 중 이 사유로 이미 재고 이력이 있는 것 */
    public Set<Long> findProcessedOrderItemIds(Collection<Long> orderItemIds, StockChangeType changeType) {
        return new HashSet<>(stockHistoryRepository.findOrderItemIds(orderItemIds, changeType));
    }

    /** 판매 기능을 쓸 수 있는 판매자 ID. 판매자가 아니거나 승인 상태가 아니면 empty (샘플 데이터용) */
    public Optional<Long> findSellableSellerId(Long memberId) {
        return memberApi.findSellerByMemberId(memberId)
                .filter(SellerDto::canSell)
                .map(SellerDto::sellerId);
    }

    public Category getChildCategory(String parentName, String name) {
        return categoryRepository.findByParentNameAndName(parentName, name)
                .orElseThrow(() -> new DomainException(ProductErrorCode.CATEGORY_NOT_FOUND));
    }

    /**
     * 검색에 쓸 사용 중인 소분류 ID. 없으면 전체, 대분류면 그 아래 소분류, 소분류면 그것 하나.
     * 사용 중지된 소분류는 빠지므로 결과가 비어 있을 수 있다. 없는 카테고리면 404.
     */
    public List<Long> getDisplayableCategoryIds(Long categoryId) {
        List<Category> activeChildren = categoryRepository.findAll().stream()
                .filter(category -> !category.isRoot() && category.isActive())
                .toList();
        if (categoryId == null) {
            return activeChildren.stream().map(Category::getId).toList();
        }

        Category category = getCategory(categoryId);
        return activeChildren.stream()
                .filter(child -> category.isRoot()
                        ? child.getParent().getId().equals(categoryId)
                        : child.getId().equals(categoryId))
                .map(Category::getId)
                .toList();
    }

    /** 상품 ID → 삭제되지 않은 옵션 목록 (ID 순). 옵션이 없는 상품은 키가 없다. */
    public Map<Long, List<ProductOption>> getOptionsByProductId(Collection<Long> productIds) {
        if (productIds.isEmpty()) return Map.of();

        return productOptionRepository.findAllByProductIdInAndDeletedAtIsNullOrderByIdAsc(productIds).stream()
                .collect(Collectors.groupingBy(option -> option.getProduct().getId()));
    }

    /** 판매자 ID → 상호명. 회원 컨텍스트에 한 번만 묻는다. 없는 판매자는 키가 없다. */
    public Map<Long, String> getBusinessNames(Collection<Long> sellerIds) {
        if (sellerIds.isEmpty()) return Map.of();

        return memberApi.getSellers(sellerIds).stream()
                .collect(Collectors.toMap(SellerDto::sellerId, SellerDto::businessName));
    }

    /** 구매자에게 보여 줄 수 있는 상품. 없거나, 판매 중이 아니거나, 카테고리가 사용 중지면 404. */
    public Product getDisplayableProduct(Long productId) {
        return productRepository.findById(productId)
                .filter(Product::isDisplayable)
                .orElseThrow(() -> new DomainException(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    /** 판매자 본인의 삭제되지 않은 상품. 없거나, 다른 판매자의 상품이거나, 삭제됐으면 404. */
    public Product getSellerProduct(Long sellerId, Long productId) {
        return productRepository.findById(productId)
                .filter(product -> product.getSellerId().equals(sellerId) && !product.isDeleted())
                .orElseThrow(() -> new DomainException(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    /**
     * 판매자 본인의 삭제되지 않은 상품을 쓰기 잠금하고 DB 값으로 다시 읽는다 (삭제·중지 요청용).
     * 주문이 같은 상품을 잠그고 있으면 끝날 때까지 기다린다. 없거나 다른 판매자 상품이거나 삭제됐으면 404.
     */
    public Product lockSellerProduct(Long sellerId, Long productId) {
        Product product = lockProduct(productId);
        if (!product.getSellerId().equals(sellerId) || product.isDeleted()) {
            throw new DomainException(ProductErrorCode.PRODUCT_NOT_FOUND);
        }
        return product;
    }

    /**
     * 상품을 쓰기 잠금하고 DB 값으로 다시 읽는다 (관리자 중지용). 삭제된 상품도 돌려준다. 없으면 404.
     * 주문이 같은 상품을 잠그고 있으면 끝날 때까지 기다린다.
     */
    public Product lockProduct(Long productId) {
        Product product = productRepository.findForUpdateById(productId)
                .orElseThrow(() -> new DomainException(ProductErrorCode.PRODUCT_NOT_FOUND));
        entityManager.refresh(product);
        return product;
    }

    /**
     * 판매 중지 요청의 상품을 먼저 쓰기 잠금한 뒤 요청을 DB 값으로 다시 읽는다 (관리자 승인·반려용). 없으면 404.
     * 상품 삭제·판매자 중지 요청·다른 관리자의 처리도 같은 상품을 먼저 잠그므로, 겹치면 한쪽이 끝난 뒤의 상태로 판단한다.
     */
    public ProductStopRequest lockStopRequest(Long requestId) {
        ProductStopRequest request = productStopRequestRepository.findById(requestId)
                .orElseThrow(() -> new DomainException(ProductErrorCode.STOP_REQUEST_NOT_FOUND));
        lockProduct(request.getProduct().getId());
        entityManager.refresh(request);
        return request;
    }

    /**
     * 관리자 상품 목록의 카테고리 조건. null이면 조건 없음, 대분류면 그 자신과 아래 소분류, 소분류면 그것 하나.
     * 사용 중지된 카테고리도 포함한다. 없는 카테고리면 404.
     */
    public List<Long> getCategoryIdsForAdmin(Long categoryId) {
        if (categoryId == null) return null;

        Category category = getCategory(categoryId);
        if (!category.isRoot()) return List.of(categoryId);

        return categoryRepository.findAll().stream()
                .filter(c -> c.getId().equals(categoryId) || (!c.isRoot() && c.getParent().getId().equals(categoryId)))
                .map(Category::getId)
                .toList();
    }

    /** 판매자 본인 상품(삭제 안 됨)의 옵션. 상품이 없거나 남의 것이거나, 옵션이 그 상품의 것이 아니면 404. */
    public ProductOption getSellerOption(Long sellerId, Long productId, Long optionId) {
        Product product = getSellerProduct(sellerId, productId);
        return productOptionRepository.findById(optionId)
                .filter(option -> option.getProduct().getId().equals(product.getId()))
                .orElseThrow(() -> new DomainException(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    /** 판매자 상품 수정 폼을 채우는 값. 이미지 ID는 수정할 때 남길 상세 이미지를 고르는 데 쓴다. */
    public SellerProductFormDto getSellerProductForm(Long sellerId, Long productId) {
        Product product = getSellerProduct(sellerId, productId);
        Category category = product.getCategory();

        return new SellerProductFormDto(
                product.getId(), product.getName(), product.getPrice(), product.getDescription(),
                category.getParent().getId(), category.getId(),
                productImageSupport.getThumbnail(productId), productImageSupport.getDetailImages(productId));
    }

    /** 삭제되지 않은 옵션이 없거나 모두 재고 0이면 품절 */
    public static boolean isSoldOut(List<ProductOption> options) {
        return options.stream().allMatch(ProductOption::isSoldOut);
    }

    /**
     * 상품 단위로 구매자에게 보여 줄 남은 수량. 삭제되지 않은 옵션의 재고 합계가
     * 1 ~ {@value ProductOption#LOW_STOCK_THRESHOLD}개일 때만 주고, 그 외(품절 포함)에는 null.
     */
    public static Integer getLowStockRemaining(List<ProductOption> options) {
        int total = options.stream().mapToInt(ProductOption::getStock).sum();
        return (total > 0 && total <= ProductOption.LOW_STOCK_THRESHOLD) ? total : null;
    }

    public long countProducts() {
        return productRepository.count();
    }

    /**
     * 판매 기능을 쓸 수 있는 판매자의 ID(seller.id)를 돌려준다.
     * role이 SELLER여도 제재·철회된 판매자가 있으므로 요청마다 승인 상태를 다시 확인한다.
     * 승인 상태가 아니면 회원 컨텍스트가 403-SELLER_NOT_APPROVED를 던진다.
     */
    public Long getSellableSellerId(Long memberId) {
        return memberApi.getApprovedSellerByMemberId(memberId).sellerId();
    }

    public Category getCategory(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new DomainException(ProductErrorCode.CATEGORY_NOT_FOUND));
    }

    public long countCategories() {
        return categoryRepository.count();
    }

    /** 사용 중인 카테고리만 정렬 순서대로 트리로 만든다. */
    public List<CategoryTreeDto> getCategoryTree() {
        List<Category> categories = categoryRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .filter(Category::isActive)
                .toList();
        Map<Long, List<Category>> childrenByParentId = groupChildrenByParentId(categories);

        return categories.stream()
                .filter(Category::isRoot)
                .map(root -> new CategoryTreeDto(
                        root.getId(), root.getName(), root.getDepth(), root.getSortOrder(),
                        childrenByParentId.getOrDefault(root.getId(), List.of()).stream()
                                .map(child -> new CategoryTreeDto(
                                        child.getId(), child.getName(), child.getDepth(), child.getSortOrder(), List.of()))
                                .toList()))
                .toList();
    }

    /** 사용 중지된 카테고리까지 정렬 순서대로 트리로 만든다. */
    public List<AdminCategoryTreeDto> getCategoryTreeForAdmin() {
        List<Category> categories = categoryRepository.findAllByOrderBySortOrderAscIdAsc();
        Map<Long, List<Category>> childrenByParentId = groupChildrenByParentId(categories);

        return categories.stream()
                .filter(Category::isRoot)
                .map(root -> new AdminCategoryTreeDto(
                        root.getId(), root.getName(), root.getDepth(), root.getSortOrder(), root.isActive(),
                        childrenByParentId.getOrDefault(root.getId(), List.of()).stream()
                                .map(child -> new AdminCategoryTreeDto(
                                        child.getId(), child.getName(), child.getDepth(), child.getSortOrder(),
                                        child.isActive(), List.of()))
                                .toList()))
                .toList();
    }

    /** 정렬 순서를 유지한 채 소분류를 부모 ID별로 묶는다. */
    private Map<Long, List<Category>> groupChildrenByParentId(List<Category> categories) {
        return categories.stream()
                .filter(category -> !category.isRoot())
                .collect(Collectors.groupingBy(category -> category.getParent().getId()));
    }
}
