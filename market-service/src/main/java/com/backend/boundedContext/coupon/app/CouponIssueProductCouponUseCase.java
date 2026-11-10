package com.backend.boundedContext.coupon.app;

import com.backend.boundedContext.coupon.app.dto.ProductCouponIssueCommand;
import com.backend.boundedContext.coupon.app.dto.ProductCouponIssueResultDto;
import com.backend.boundedContext.coupon.domain.Coupon;
import com.backend.boundedContext.coupon.domain.CouponErrorCode;
import com.backend.boundedContext.coupon.domain.Partnership;
import com.backend.boundedContext.coupon.domain.ProductCoupon;
import com.backend.boundedContext.coupon.out.CouponProductPort;
import com.backend.boundedContext.coupon.out.CouponRepository;
import com.backend.boundedContext.coupon.out.CouponSellerPort;
import com.backend.boundedContext.coupon.out.InfluencerPickPort;
import com.backend.boundedContext.coupon.out.ProductCouponRepository;
import com.backend.global.exception.DomainException;
import com.backend.global.exception.GlobalErrorCode;
import com.backend.shared.product.dto.ProductSnapshotDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 상품 쿠폰 발행 (판매자) → 쿠폰 코드 생성
 *
 * <p>확인 순서와 잠금:
 * <ol>
 *     <li>판매 가능한 판매자인지 (403-SELLER_NOT_APPROVED)</li>
 *     <li>협약을 쓰기 잠금하고, 내 협약인지(403-FORBIDDEN), 진행 중인지(409-PARTNERSHIP_NOT_ACTIVE) 본다.
 *         같은 협약의 종료나 다른 발행과 겹치지 않으므로 기간 겹침 확인도 이 잠금 안에서 안전하다.</li>
 *     <li>상품을 읽기 잠금하고, 내 상품인지(400-PRODUCT_NOT_OWNED), 판매 중인지(409-PRODUCT_NOT_ON_SALE) 본다.
 *         판매 중지, 삭제가 이 사이에 끼어들어 중지되지 않은 쿠폰이 남는 일을 막는다.</li>
 *     <li>협약 인플루언서가 pick한 상품인지 (400-PRODUCT_NOT_PICKED)</li>
 *     <li>유효 기간, 수량, 할인 내용 (400-COUPON_PERIOD_INVALID, 400-VALIDATION_FAILED)</li>
 *     <li>같은 인플루언서와 상품으로 기간이 겹치는 운영 중 쿠폰이 있는지 (409-COUPON_PERIOD_OVERLAPPED)</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class CouponIssueProductCouponUseCase {

    // 코드가 이미 쓰이고 있으면 다시 만드는 최대 횟수 (32^10 경우의 수라 실제로는 거의 한 번에 끝난다)
    static final int CODE_RETRY_LIMIT = 5;

    private final CouponSupport couponSupport;
    private final CouponSellerPort couponSellerPort;
    private final CouponProductPort couponProductPort;
    private final InfluencerPickPort influencerPickPort;
    private final CouponRepository couponRepository;
    private final ProductCouponRepository productCouponRepository;
    private final CouponCodeGenerator couponCodeGenerator;

    public ProductCouponIssueResultDto issue(Long memberId, ProductCouponIssueCommand command) {
        LocalDateTime now = LocalDateTime.now();
        Long sellerId = couponSellerPort.getApprovedSellerId(memberId);

        Partnership partnership = couponSupport.lockPartnership(command.partnershipId());
        if (!partnership.isOwnedBySeller(sellerId)) {
            throw new DomainException(GlobalErrorCode.FORBIDDEN);
        }
        if (!partnership.isActive()) {
            throw new DomainException(CouponErrorCode.PARTNERSHIP_NOT_ACTIVE);
        }

        checkProduct(sellerId, command.productId());
        if (!influencerPickPort.isPicked(partnership.getInfluencerId(), command.productId())) {
            throw new DomainException(CouponErrorCode.PRODUCT_NOT_PICKED);
        }

        Coupon coupon = Coupon.createProductCoupon(sellerId, command.name(), command.quantityLimited(), command.totalQuantity(),
                command.validFrom(), command.validUntil(), now);
        String code = newCode();
        // 할인 내용 검증이 쿠폰 저장보다 먼저 되도록 상품 쿠폰을 먼저 만든다
        ProductCoupon productCoupon = ProductCoupon.create(coupon, partnership.getId(), partnership.getInfluencerId(),
                command.productId(), code, command.discount());

        couponSupport.checkNotOverlapped(partnership.getInfluencerId(), command.productId(),
                coupon.getValidFrom(), coupon.getValidUntil(), null);

        couponRepository.save(coupon);
        productCouponRepository.save(productCoupon);
        return new ProductCouponIssueResultDto(coupon.getId(), code);
    }

    // 상품을 읽기 잠금한 뒤 내 판매 중 상품인지 본다. 없는 상품도 내 상품이 아닌 것으로 본다
    private void checkProduct(Long sellerId, Long productId) {
        couponProductPort.lockProductForShare(productId);
        ProductSnapshotDto product = couponProductPort.getProducts(List.of(productId)).stream()
                .findFirst()
                .orElseThrow(() -> new DomainException(CouponErrorCode.PRODUCT_NOT_OWNED));

        if (!sellerId.equals(product.sellerId())) {
            throw new DomainException(CouponErrorCode.PRODUCT_NOT_OWNED);
        }
        if (!product.onSale()) {
            throw new DomainException(CouponErrorCode.PRODUCT_NOT_ON_SALE);
        }
    }

    private String newCode() {
        for (int i = 0; i < CODE_RETRY_LIMIT; i++) {
            String code = couponCodeGenerator.generate();
            if (!productCouponRepository.existsByCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("쿠폰 코드를 만들지 못했습니다. 다시 시도해 주세요.");
    }
}
