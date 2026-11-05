package com.backend.boundedContext.product.out.wishlist;

import com.backend.boundedContext.product.domain.wishlist.Wishlist;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;

public interface WishlistRepository extends JpaRepository<Wishlist, Long> {

    /**
     * 찜이 없을 때만 넣고 넣은 행 수(1 또는 0)를 돌려준다.
     * 같은 요청이 동시에 와도 유니크 제약에서 한쪽만 들어가고, 다른 쪽은 오류 없이 0이 된다
     * (저장 후 제약 위반을 잡는 방식은 PostgreSQL에서 트랜잭션 전체가 실패 상태가 되므로 쓰지 않는다).
     * 생성·수정 시각은 JPA Auditing을 거치지 않으므로 앱 시각(KST)을 넘긴다.
     */
    @Modifying
    @Query(value = """
            insert into wishlist (member_id, product_id, created_at, updated_at)
            values (:memberId, :productId, :now, :now)
            on conflict (member_id, product_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(Long memberId, Long productId, LocalDateTime now);

    /** 찜을 지우고 지운 행 수(1 또는 0)를 돌려준다 */
    @Modifying
    @Query("delete from Wishlist w where w.memberId = :memberId and w.product.id = :productId")
    int deleteByMemberIdAndProductId(Long memberId, Long productId);

    boolean existsByMemberIdAndProductId(Long memberId, Long productId);

    /** 회원의 찜 목록. 판매 가능 여부를 보려고 상품과 카테고리를 함께 읽는다 */
    @Query(value = "select w from Wishlist w join fetch w.product p join fetch p.category where w.memberId = :memberId",
            countQuery = "select count(w) from Wishlist w where w.memberId = :memberId")
    Page<Wishlist> findPageByMemberId(Long memberId, Pageable pageable);
}
