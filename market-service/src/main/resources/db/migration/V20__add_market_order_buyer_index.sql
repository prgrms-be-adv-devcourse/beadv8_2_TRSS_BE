-- 구매자 주문 목록(최신순)·최근 주문 조회용 인덱스 (TRSS-307)
-- Order 엔티티 @Table(indexes)에는 있었지만 마이그레이션에 없어 운영 DB에 만들어지지 않았다
-- 수동으로 이미 만든 환경이 있어도 실패하지 않게 if not exists
create index if not exists idx_market_order_buyer on market_order (buyer_id, created_at);
