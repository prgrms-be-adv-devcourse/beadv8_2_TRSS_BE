-- processed_event.consumer를 리스너마다 나눈다 (TRSS-307)
-- 배송 완료 리스너(OrderDeliveryEventListener)와 판매자 이벤트 리스너(ProductSellerEventListener)가 둘 다 'market'으로 기록해 왔다
-- Kafka groupId(market)는 그대로라 오프셋은 이어진다. 처리 기록만 새 이름으로 옮긴다
-- 기존 행이 어느 리스너의 것인지 구분할 컬럼이 없어 두 이름으로 모두 복사한다.
-- 이벤트 ID는 UUID라 다른 토픽 이벤트와 겹치지 않으므로, 상대 리스너 이름으로 복사된 행은 아무 이벤트도 막지 않는다
insert into processed_event (event_id, consumer, processed_at)
select event_id, 'market-order-delivery', processed_at from processed_event where consumer = 'market'
on conflict (event_id, consumer) do nothing;

insert into processed_event (event_id, consumer, processed_at)
select event_id, 'market-product-seller', processed_at from processed_event where consumer = 'market'
on conflict (event_id, consumer) do nothing;
-- 'market' 행은 지우지 않는다. 이전 버전으로 되돌려도 이미 처리한 이벤트를 다시 처리하지 않게 남겨 둔다
