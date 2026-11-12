-- 결제 중(PAYING) 상태와 결제 결과 재확인 (TRSS-318)
-- 결제는 HTTP라 주문 트랜잭션에 참여하지 못한다. 결제 요청 전에 주문을 PAYING으로 바꾸고 이번 시도의 멱등키·시작 시각을 커밋한 뒤 결제를 부른다
-- payment_idempotency_key: 마지막 결제 시도의 멱등키. 결과 반영은 PAYING이고 이 키가 같을 때만 한다. 재확인 스케줄러가 같은 키로 pay를 다시 부른다
-- paying_started_at: PAYING이 된 시각. 재확인 스케줄러는 이 시각이 일정 시간(30초) 지난 주문만 고른다
-- 이 컬럼이 생기기 전 주문은 둘 다 null. status 컬럼(varchar 30)은 그대로 PAYING을 담는다
-- V11은 다른 작업 브랜치(TRSS-212 상품 할인)가 쓰고 있어 V12로 둔다
alter table market_order
    add column payment_idempotency_key varchar(64),
    add column paying_started_at timestamp(6);

create index idx_market_order_status_paying on market_order (status, paying_started_at);
