package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.app.dto.AdminCategoryTreeDto;
import com.backend.boundedContext.product.app.dto.CategoryTreeDto;
import com.backend.boundedContext.product.domain.Category;
import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductErrorCode;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.domain.StockChangeType;
import com.backend.boundedContext.product.out.CategoryRepository;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.boundedContext.product.out.ProductRepository;
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
    private final MemberApi memberApi;
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
