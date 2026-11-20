-- 상품 행동 지표(판매량)를 상품 검색 색인에 넣으려고 주문 쪽을 상품 기준·시각 기준으로 읽는다 (TRSS-290)
-- 상품별 최근 결제 수량: 주문 항목을 상품 ID로 모은다
create index idx_market_order_item_product on market_order_item (product_id);
-- 판매량 집계 구간(최근 30일) 밖으로 나간 주문: 결제 시각으로 찾는다
create index idx_market_order_paid_at on market_order (paid_at) where paid_at is not null;
-- 지난 실행 이후 상태가 바뀐 주문(결제·취소·반품): 상태 이력의 생성 시각으로 찾는다 (1분마다 읽는다)
create index idx_order_status_history_created_at on order_status_history (created_at);
