-- 할인 경계 재색인(상품 검색 ES). 스케줄러가 10초마다 "최근 구간에 시작했거나 끝난 할인"을 찾는다.
-- 시작·종료 시각 조건이 OR라 각각 인덱스를 두고, 삭제된 할인은 대상이 아니므로 부분 인덱스로 둔다.
create index idx_product_discount_starts_at on product_discount (starts_at) where deleted_at is null;
create index idx_product_discount_ends_at on product_discount (ends_at) where deleted_at is null;
