package com.backend.boundedContext.recommend.out;

import com.backend.boundedContext.recommend.domain.RecommendProduct;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendProductRepository extends JpaRepository<RecommendProduct, Long> {
}
