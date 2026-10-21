-- 판매자 주문 항목 조회: seller_id로 찾고 주문을 조인한다
create index idx_market_order_item_seller on market_order_item (seller_id, order_id);
