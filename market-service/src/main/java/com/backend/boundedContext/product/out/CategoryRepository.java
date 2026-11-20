package com.backend.boundedContext.product.out;

import com.backend.boundedContext.product.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findAllByOrderBySortOrderAscIdAsc();

    boolean existsByParentAndName(Category parent, String name);

    boolean existsByParentAndNameAndIdNot(Category parent, String name, Long id);

    /** 대분류 이름과 소분류 이름으로 소분류를 찾는다 (샘플 데이터용) */
    Optional<Category> findByParentNameAndName(String parentName, String name);

    /** 같은 부모 아래 정렬 순서가 가장 큰 카테고리 */
    Optional<Category> findTopByParentOrderBySortOrderDesc(Category parent);
}
