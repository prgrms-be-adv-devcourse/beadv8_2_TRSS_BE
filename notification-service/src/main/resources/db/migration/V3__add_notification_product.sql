-- 알림 한 줄에 보여 줄 대표 상품명(주문의 첫 상품)·상품 개수(주문 항목 수)
-- 결제 완료 알림에 저장하고, 같은 주문의 배송 알림은 결제 완료 알림의 값을 다시 쓴다
-- 결제 완료 알림이 없거나 이 컬럼 추가 전 알림은 null
alter table notification
    add column product_name varchar(100),
    add column item_count integer;
