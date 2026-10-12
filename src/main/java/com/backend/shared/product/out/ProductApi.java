package com.backend.shared.product.out;

import com.backend.shared.product.dto.OptionSnapshotDto;
import com.backend.shared.product.dto.StockChangeDto;
import com.backend.shared.product.dto.StockRestoreReason;

import java.util.Collection;
import java.util.List;

/**
 * 상품 컨텍스트가 다른 컨텍스트에 제공하는 내부 API.
 * 장바구니·주문은 옵션 단위로 담고, 재고도 옵션 단위로 관리한다.
 * 차감·복원은 호출한 쪽 트랜잭션에 참여하므로, 실패하면 호출한 쪽 작업도 함께 롤백된다.
 * 예외를 잡아서 무시하면 주문과 재고가 어긋나므로 그대로 위로 던진다.
 */
public interface ProductApi {

    /**
     * 옵션 여러 개의 현재 정보를 조회한다. 장바구니 담기·조회와 주문 생성에서 쓴다.
     *
     * <ul>
     *   <li>삭제된 상품·옵션도 결과에 포함한다 (saleable = false).</li>
     *   <li>존재하지 않는 ID만 결과에서 빠진다.</li>
     *   <li>결과 순서는 보장하지 않으므로 optionId로 Map을 만들어 쓴다.</li>
     * </ul>
     */
    List<OptionSnapshotDto> getOptions(Collection<Long> optionIds);

    /**
     * 주문 생성 시 재고를 차감한다 (재고 이력 사유: ORDER_DEDUCT).
     * 주문 항목을 저장해 orderItemId가 생긴 뒤, 같은 트랜잭션에서 호출한다.
     *
     * <ul>
     *   <li>옵션 ID 오름차순으로 잠근다.</li>
     *   <li>판매 불가 (saleable = false): 409-PRODUCT_NOT_ON_SALE</li>
     *   <li>재고 부족 (재고 0 포함): 400-OUT_OF_STOCK</li>
     *   <li>하나라도 실패하면 전체가 롤백된다.</li>
     *   <li>재고만 바꾸고 상품 상태는 바꾸지 않는다. 품절은 재고로 계산한다.</li>
     *   <li>이미 차감한 주문 항목으로 다시 호출하면 아무것도 하지 않고 정상 종료한다.</li>
     * </ul>
     */
    void deductStock(List<StockChangeDto> items);

    /**
     * 재고를 복원한다. 주문 취소 상태 변경에 성공한 주문만 호출한다.
     *
     * <ul>
     *   <li>재고만 바꾸고 상품 상태는 바꾸지 않는다. STOPPED 상품도 재고만 늘어난다.</li>
     *   <li>삭제된 상품·옵션은 ORDER_RESTORE일 때만 재고를 늘린다.
     *       RETURN_RESTORE면 삭제된 상품은 409-PRODUCT_DELETED, 삭제된 옵션은 409-OPTION_DELETED
     *       (상품을 먼저 본다. 주문 모듈이 반품 승인 때 미리 걸러서 호출하지 않는다).</li>
     *   <li>같은 주문 항목·사유로 다시 호출하면 아무것도 하지 않고 정상 종료한다.</li>
     *   <li>차감한 적 없는 주문 항목이면 IllegalStateException (호출 순서 오류).</li>
     * </ul>
     */
    void restoreStock(List<StockChangeDto> items, StockRestoreReason reason);
}
