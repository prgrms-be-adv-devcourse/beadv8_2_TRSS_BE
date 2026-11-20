package com.backend.boundedContext.product.out;

import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    /**
     * 상품을 ID 순으로 읽기 잠금(SELECT ... FOR SHARE)한다.
     * 재고를 바꾸는 동안 판매 중지·삭제가 끼어들지 못하게 막고, 주문끼리는 서로 막지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from Product p where p.id in :ids order by p.id")
    List<Product> findAllForShareByIdIn(Collection<Long> ids);

    /**
     * 이 상태이면서 품절이 아닌 상품의 소분류별 개수 (카테고리 트리의 상품 수).
     * 품절 판단은 검색의 품절 제외(ProductSpecifications.inStock)와 같다. 상품이 없는 소분류는 결과에 없다
     */
    @Query("select new com.backend.boundedContext.product.out.CategoryProductCount(p.category.id, count(p)) "
            + "from Product p where p.status = :status "
            + "and exists (select 1 from OptionStock s join s.option o where o.product = p and o.deletedAt is null and s.stock > 0) "
            + "group by p.category.id")
    List<CategoryProductCount> countByCategory(ProductSaleStatus status);

    /** 상품 하나를 쓰기 잠금(SELECT ... FOR UPDATE)한다 (삭제·중지 요청·관리자 중지처럼 상품 상태를 보고 바꾸는 작업용) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findForUpdateById(Long id);

    /**
     * 판매자의 이 상태 상품을 ID 순으로 쓰기 잠금(SELECT ... FOR UPDATE)한다 (판매자 전체 중지·재개용).
     * 주문이 상품을 잠그는 순서(ID 순)와 같아서 동시에 들어와도 서로 기다릴 뿐 데드락이 나지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.sellerId = :sellerId and p.status = :status order by p.id")
    List<Product> findAllForUpdateBySellerIdAndStatus(Long sellerId, ProductSaleStatus status);

    /** 판매자의 이 상태 상품 ID (상호가 바뀌어 다시 색인할 상품). 잠그지 않는다 */
    @Query("select p.id from Product p where p.sellerId = :sellerId and p.status = :status order by p.id")
    List<Long> findIdsBySellerIdAndStatus(Long sellerId, ProductSaleStatus status);

    /** 이 상태 상품의 ID를 afterId 다음부터 ID 순으로 limit개 (검색 전체 재색인용) */
    @Query("select p.id from Product p where p.status = :status and p.id > :afterId order by p.id")
    List<Long> findIdsByStatusAfter(ProductSaleStatus status, Long afterId, Limit limit);

    /** 소분류의 이 상태가 아닌 상품 ID */
    @Query("select p.id from Product p where p.category.id = :categoryId and p.status <> :status")
    List<Long> findIdsByCategoryIdAndStatusNot(Long categoryId, ProductSaleStatus status);

    /**
     * since 이후 색인 문서 값이 바뀌었을 수 있는 상품 ID (전체 재색인 중 바뀐 상품을 새 인덱스에 다시 반영할 때).
     * 상품·옵션·재고·할인 엔티티는 모두 updated_at이 자동으로 갱신되고(@LastModifiedDate), 이 테이블들에는 벌크 UPDATE가 없다.
     */
    @Query(value = """
            select p.id from product p where p.updated_at >= :since
            union
            select o.product_id from product_option o join option_stock s on s.option_id = o.id
            where o.updated_at >= :since or s.updated_at >= :since
            union
            select d.product_id from product_discount d where d.updated_at >= :since
            """, nativeQuery = true)
    List<Long> findIdsChangedSince(LocalDateTime since);
}
