package com.backend.boundedContext.product.out;

import com.backend.boundedContext.product.domain.ProductStopRequest;
import com.backend.boundedContext.product.domain.ProductStopRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface ProductStopRequestRepository extends JpaRepository<ProductStopRequest, Long> {

    boolean existsByProductIdAndStatus(Long productId, ProductStopRequestStatus status);

    /** 여러 상품의 이 상태 요청 (판매 종료 시 심사 중 요청 닫기용) */
    List<ProductStopRequest> findAllByProductIdInAndStatus(Collection<Long> productIds, ProductStopRequestStatus status);

    /** 이 상태 요청이 있는 상품 ID (판매자 목록의 "중지 요청 심사 중" 표시용) */
    @Query("select r.product.id from ProductStopRequest r where r.product.id in :productIds and r.status = :status")
    List<Long> findProductIdsByProductIdInAndStatus(Collection<Long> productIds, ProductStopRequestStatus status);
}
