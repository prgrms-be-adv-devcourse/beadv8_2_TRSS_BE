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
     *   <li>존재하지 않는 옵션 ID: 404-PRODUCT_NOT_FOUND</li>
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
     *   <li>존재하지 않는 옵션 ID: 404-PRODUCT_NOT_FOUND</li>
     * </ul>
     */
    void restoreStock(List<StockChangeDto> items, StockRestoreReason reason);

    /**
     * 재고를 복원할 주문을 잠그기 전에, 그 주문에 담긴 상품을 먼저 잠근다.
     * 구매자 주문 취소·결제 만료·반품 승인처럼 주문을 잠근 뒤 restoreStock을 부르는 흐름에서 쓴다.
     *
     * <p>잠금 순서는 항상 상품 → 주문이다. 상품 판매 중지·삭제는 상품을 잠근 뒤 cancelPendingOrders로 주문을 잠그므로,
     * 주문을 먼저 잠그고 상품을 잠그면 서로 기다리다 데드락이 난다.
     *
     * <ul>
     *   <li>호출 순서: 주문 항목의 productId 모으기 → 이 메서드 → 주문 잠금 → 주문 상태 다시 확인 → 취소·restoreStock</li>
     *   <li>상품을 ID 순으로 읽기 잠금(FOR SHARE)한다. 구매자 취소끼리는 서로 막지 않는다.</li>
     *   <li>상품 판매 중지·삭제가 진행 중이면 끝날 때까지 기다린다. 그사이 주문이 취소됐을 수 있으므로 주문 상태를 다시 확인한다.</li>
     *   <li>잠금은 호출한 쪽 트랜잭션이 끝날 때 풀린다. 트랜잭션 밖에서 부르면 IllegalTransactionStateException.</li>
     *   <li>없는 상품 ID는 건너뛰고, 상품 상태는 확인하지 않는다 (삭제·중지된 상품도 잠근다).</li>
     *   <li>cancelPendingOrders 안에서는 상품이 이미 잠겨 있으므로 부르지 않아도 된다.</li>
     * </ul>
     */
    void lockProductsForOrderChange(Collection<Long> productIds);

    /**
     * 판매자의 판매 중 상품을 모두 중지한다. 판매자 철회 요청·관리자 제재 때 회원 컨텍스트가 같은 트랜잭션에서 호출한다.
     *
     * <ul>
     *   <li>ON_SALE 상품만 STOPPED로 바꾸고 stoppedBy = SELLER_WITHDRAWN, 사유·시각을 남긴다.</li>
     *   <li>이미 중지(관리자·판매 중지 요청)되었거나 삭제된 상품은 건드리지 않는다.</li>
     *   <li>중지한 상품이 들어 있는 결제 대기 주문은 OrderApi.cancelPendingOrders(PRODUCT_STOPPED)로 취소한다.</li>
     *   <li>판매 중 상품이 없으면 아무것도 하지 않는다. 다시 호출해도 결과가 같다.</li>
     * </ul>
     */
    void stopAllBySeller(Long sellerId, String reason);

    /**
     * 판매자 철회로 중지된 상품을 다시 판매 중으로 되돌린다. 철회 거절 때 회원 컨텍스트가 같은 트랜잭션에서 호출한다.
     *
     * <ul>
     *   <li>stoppedBy = SELLER_WITHDRAWN인 STOPPED 상품만 ON_SALE로 바꾸고 중지 사유·주체·시각을 비운다.</li>
     *   <li>관리자·판매 중지 요청으로 중지된 상품과 삭제된 상품은 그대로 둔다.</li>
     *   <li>철회 요청 때 취소된 결제 대기 주문은 되살리지 않는다.</li>
     *   <li>되돌릴 상품이 없으면 아무것도 하지 않는다. 다시 호출해도 결과가 같다.</li>
     * </ul>
     */
    void resumeAllBySeller(Long sellerId);
}
