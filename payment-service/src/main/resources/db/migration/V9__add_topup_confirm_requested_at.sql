-- 충전 건의 승인 요청 시각(PROCESSING 선점 시각). 헥토 조회에서 결제 요청 날짜로 거래를 못 찾으면 이 날짜로 한 번 더 찾는다 (자정을 넘긴 충전 건)
-- 지금까지의 충전 건은 정확한 승인 요청 시각을 알 수 없어 비워 둔다 (비어 있으면 결제 요청 날짜로만 조회)
alter table topup add column confirm_requested_at timestamp(6);
