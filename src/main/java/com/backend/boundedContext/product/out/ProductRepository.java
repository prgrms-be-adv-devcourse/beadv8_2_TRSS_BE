package com.backend.boundedContext.product.out;

import com.backend.boundedContext.product.domain.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    /**
     * 상품을 ID 순으로 읽기 잠금(SELECT ... FOR SHARE)한다.
     * 재고를 바꾸는 동안 판매 중지·삭제가 끼어들지 못하게 막고, 주문끼리는 서로 막지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from Product p where p.id in :ids order by p.id")
    List<Product> findAllForShareByIdIn(Collection<Long> ids);
}
