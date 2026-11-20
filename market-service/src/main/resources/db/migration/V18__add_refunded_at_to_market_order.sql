-- 반품 환불 완료 시각. 결제 서비스가 환불을 끝낸 시각을 환불 확인 스케줄러가 기록한다(RETURNED만 값이 있음).
-- 반품 승인(returned_at) 뒤에도 null이면 아직 환불 전이다: 회원 탈퇴를 막고, 오래 null이면 ERROR로 알린다.
alter table market_order add column refunded_at timestamp(6);

-- 환불 지연 알림(ERROR 로그)을 보낸 시각. 환불이 계속 오지 않아도 알림은 한 번만 보낸다.
alter table market_order add column refund_alerted_at timestamp(6);

-- 환불 확인 스케줄러 대상(반품 승인됐는데 환불 전인 주문)만 담는 인덱스
create index idx_market_order_refund_pending on market_order (returned_at, id)
    where status = 'RETURNED' and refunded_at is null;
