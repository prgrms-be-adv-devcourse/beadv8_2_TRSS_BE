-- 주문 생성 멱등 키: 클라이언트가 주문서 화면에서 만든 키를 Idempotency-Key 헤더로 보낸다
-- 같은 구매자가 같은 키로 다시 요청하면 처음 만든 주문을 돌려준다. 키 없이 만든 주문은 null
alter table market_order add column idempotency_key varchar(64);

-- 구매자별로 키가 겹치지 않게 한다 (null끼리는 겹쳐도 된다)
create unique index uk_market_order_buyer_idempotency on market_order (buyer_id, idempotency_key);
