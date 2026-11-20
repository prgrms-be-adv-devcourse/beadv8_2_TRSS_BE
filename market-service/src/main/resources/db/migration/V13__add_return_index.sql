-- 관리자 반품 목록: 반품을 요청한 주문만 요청 시각 순으로 본다
-- 반품을 요청하지 않은 주문(대부분)은 인덱스에 넣지 않는다(부분 인덱스)
create index idx_market_order_return_requested
    on market_order (return_requested_at, id)
    where return_requested_at is not null;
