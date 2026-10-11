package com.backend.boundedContext.product.out;

import com.backend.boundedContext.product.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findAllByOrderBySortOrderAscIdAsc();

    boolean existsByParentAndName(Category parent, String name);

    boolean existsByParentAndNameAndIdNot(Category parent, String name, Long id);

    /** 같은 부모 아래 정렬 순서가 가장 큰 카테고리 */
    Optional<Category> findTopByParentOrderBySortOrderDesc(Category parent);
}
