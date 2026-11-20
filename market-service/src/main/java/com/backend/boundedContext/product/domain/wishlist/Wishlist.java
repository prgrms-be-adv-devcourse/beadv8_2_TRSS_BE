package com.backend.boundedContext.product.domain.wishlist;

import com.backend.boundedContext.product.domain.Product;
import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import static jakarta.persistence.FetchType.LAZY;

/**
 * 구매자의 상품 찜 한 건. 회원은 ID로만 참조한다 (회원 컨텍스트의 member.id).
 * 회원·상품 한 쌍에 한 건만 둔다 (uk_wishlist_member_product). 찜 추가는 이 제약을 이용해 DB에서 한 번에 넣으므로
 * 엔티티를 직접 만들지 않는다 ({@code WishlistRepository#insertIfAbsent}).
 * {@link Product} 행은 바꾸지 않는다. 찜이 몰려도 상품 수정·재고 차감과 잠금을 다투지 않게 하기 위해서다.
 */
@Entity
@Table(name = "wishlist")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Wishlist extends BaseIdAndTime {

    @Column(nullable = false)
    private Long memberId;

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;
}
