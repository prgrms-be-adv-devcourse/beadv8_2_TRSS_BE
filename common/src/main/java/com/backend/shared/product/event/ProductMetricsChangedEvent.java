package com.backend.shared.product.event;

import com.backend.shared.event.DomainEvent;

/**
 * 상품의 행동 지표(판매량)가 바뀌었을 수 있음. 주문 결제·취소·반품과, 판매량 집계 구간(최근 30일) 밖으로 나간 판매가 있을 때
 * 판매 지표 스케줄러가 발행한다. 받는 쪽은 market 검색 색인 컨슈머뿐이다(메시지 키 productId로 DB를 다시 읽어 색인).
 * 상품 내용은 바뀌지 않았으므로 AI 추천이 받는 ProductChangedEvent와 나눴다. AI 컨슈머는 eventType 헤더로 거른다.
 *
 * @param productId 상품 ID
 */
public record ProductMetricsChangedEvent(Long productId) implements DomainEvent {

    @Override
    public String aggregateType() {
        return "PRODUCT";   // 토픽 product.events
    }

    @Override
    public Long aggregateId() {
        return productId;
    }
}
