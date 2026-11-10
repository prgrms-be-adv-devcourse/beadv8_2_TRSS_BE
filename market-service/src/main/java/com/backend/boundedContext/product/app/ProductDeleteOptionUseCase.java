package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.domain.ProductErrorCode;
import com.backend.boundedContext.product.domain.ProductOption;
import com.backend.boundedContext.product.out.ProductOptionRepository;
import com.backend.global.exception.DomainException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProductDeleteOptionUseCase {
    private final ProductSupport productSupport;
    private final ProductOptionRepository productOptionRepository;

    /**
     * 판매자가 옵션 조합을 지운다 (소프트 삭제). 지운 옵션은 장바구니·주문에서 OPTION_DELETED로 판매 불가가 된다.
     * 상품을 쓰기 잠금한 뒤 지운다. 주문 재고 차감과 판매자 재고 조정은 상품을 읽기 잠금한 뒤 재고를 잠그므로,
     * 이미 진행 중인 차감은 끝난 뒤에, 이후의 차감은 삭제가 커밋된 뒤의 옵션 상태로 판단한다. 동시에 지워도 마지막 옵션은 남는다.
     * 다른 판매자의 상품·다른 상품의 옵션은 404, 판매 중지·삭제된 상품과 이미 삭제된 옵션은 409, 마지막 남은 옵션이면 409.
     */
    public void deleteOption(Long sellerId, Long productId, Long optionId) {
        productSupport.lockSellerProduct(sellerId, productId);
        ProductOption option = productSupport.getSellerOption(sellerId, productId, optionId);

        boolean othersLeft = productOptionRepository.findAllByProductIdAndDeletedAtIsNullOrderByIdAsc(productId).stream()
                .anyMatch(other -> !other.getId().equals(optionId));
        option.delete();   // 중지·삭제 상품 409, 삭제된 옵션 409
        if (!othersLeft) {
            throw new DomainException(ProductErrorCode.LAST_OPTION_NOT_DELETABLE);
        }
    }
}
