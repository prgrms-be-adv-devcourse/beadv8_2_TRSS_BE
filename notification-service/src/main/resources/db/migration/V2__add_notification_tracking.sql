-- 배송 시작 알림의 택배사 표시 이름·송장번호. 프론트가 문구에서 잘라 쓰지 않도록 따로 저장한다
-- 배송 시작 외 알림과 이 컬럼 추가 전 알림은 null
alter table notification
    add column carrier_name varchar(30),
    add column tracking_number varchar(30);
