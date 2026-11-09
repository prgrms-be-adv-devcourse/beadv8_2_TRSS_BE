-- 주문 배송지(스냅샷)와 배송 완료 시각
-- 배송 기능 이전 주문·배송지 없이 만든 주문(프론트가 붙기 전) 때문에 모두 null을 허용한다. 새 주문의 배송지는 요청 검증으로 막는다
-- delivered_at: delivery 서비스의 DeliveryCompletedEvent를 받아 기록한다. 배송지가 있는 주문의 구매 확정·자동 확정 기준
alter table market_order
    add column receiver_name varchar(50),
    add column receiver_phone varchar(20),
    add column zip_code varchar(10),
    add column address varchar(200),
    add column address_detail varchar(200),
    add column request_message varchar(100),
    add column delivered_at timestamp(6);
