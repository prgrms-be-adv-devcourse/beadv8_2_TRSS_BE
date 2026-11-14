package com.backend.boundedContext.review.out;

import com.backend.boundedContext.review.domain.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    /** 삭제한 리뷰도 포함해서 본다 (삭제 후 재작성 불가) */
    boolean existsByOrderItemId(Long orderItemId);

    /** 회원 본인의 삭제되지 않은 리뷰. 없거나 삭제됐거나 다른 회원의 리뷰면 empty */
    @Query("select r from Review r where r.id = :reviewId and r.memberId = :memberId and r.deletedAt is null")
    Optional<Review> findActiveByIdAndMemberId(@Param("reviewId") Long reviewId, @Param("memberId") Long memberId);

    @Query("select r from Review r where r.productId = :productId and r.deletedAt is null")
    Page<Review> findActivePageByProductId(@Param("productId") Long productId, Pageable pageable);

    @Query("select r from Review r where r.memberId = :memberId and r.deletedAt is null")
    Page<Review> findActivePageByMemberId(@Param("memberId") Long memberId, Pageable pageable);

    /** 상품의 별점별 리뷰 수. 리뷰가 없는 별점은 결과에 없다 */
    @Query("""
            select new com.backend.boundedContext.review.out.RatingCount(r.rating, count(r))
            from Review r
            where r.productId = :productId and r.deletedAt is null
            group by r.rating
            """)
    List<RatingCount> countByRating(@Param("productId") Long productId);
}
