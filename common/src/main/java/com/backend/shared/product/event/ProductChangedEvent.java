package com.backend.shared.product.event;

import com.backend.shared.event.DomainEvent;

/**
 * 상품의 내용·판매 상태·품절 여부·할인·카테고리가 바뀜. 바뀐 부분이 아니라 상품의 지금 상태 전체를 담는다.
 * 받는 쪽: AI 추천(추천용 상품 사본·성분 태그·임베딩), market 검색 색인(메시지 키 productId로 DB를 다시 읽어 색인, payload는 쓰지 않음).
 * 발행 시점: 상품 등록·수정·삭제·판매 중지·중지 해제, 판매자 전체 중지·재개, 품절 여부가 바뀐 재고 차감·복원·조정,
 * 옵션 추가·삭제, 할인 등록·수정·삭제·지금 종료, 할인 시작·종료 시각(할인 경계 스케줄러), 소분류 이름·사용 여부 변경.
 * 나중에 더한 필드는 래퍼 타입이다. 그 필드가 없는 예전 메시지를 읽어도 기본형 null 오류가 나지 않게 한다.
 *
 * @param categoryName    소분류 이름 (예: 스킨케어)
 * @param beauty          뷰티 대분류의 상품인지
 * @param ingredientsText 전성분 원문. 뷰티가 아니거나 판매자가 아직 넣지 않았으면 null
 * @param price           정가 (할인 반영 안 함)
 * @param thumbnailUrl    대표 이미지 URL. 없으면 null
 * @param onSale          구매자에게 보이는 상품인지 (판매 중이고 카테고리 사용 중). 삭제·판매 중지면 false
 * @param soldOut         삭제되지 않은 옵션의 재고가 모두 0인지. 이 필드를 더하기 전에 발행한 메시지면 null
 */
public record ProductChangedEvent(
        Long productId,
        String name,
        String description,
        String categoryName,
        boolean beauty,
        String ingredientsText,
        long price,
        String thumbnailUrl,
        boolean onSale,
        Boolean soldOut
) implements DomainEvent {

    /** soldOut을 더하기 전 모양 (soldOut은 null) */
    public ProductChangedEvent(Long productId, String name, String description, String categoryName, boolean beauty,
                               String ingredientsText, long price, String thumbnailUrl, boolean onSale) {
        this(productId, name, description, categoryName, beauty, ingredientsText, price, thumbnailUrl, onSale, null);
    }

    @Override
    public String aggregateType() {
        return "PRODUCT";   // 토픽 product.events (찜 이벤트와 같은 토픽, 받는 쪽은 eventType 헤더로 거른다)
    }

    @Override
    public Long aggregateId() {
        return productId;   // 같은 상품의 이벤트는 같은 파티션으로 가서 순서대로 전달된다
    }
}
