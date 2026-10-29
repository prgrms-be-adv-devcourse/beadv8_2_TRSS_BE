package com.backend.boundedContext.product.out;

import com.backend.boundedContext.product.domain.Product;
import com.backend.boundedContext.product.domain.ProductSaleStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

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
            + "and exists (select 1 from ProductOption o where o.product = p and o.deletedAt is null and o.stock > 0) "
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
}
