package com.backend.boundedContext.product.out;

import com.backend.boundedContext.product.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
}
