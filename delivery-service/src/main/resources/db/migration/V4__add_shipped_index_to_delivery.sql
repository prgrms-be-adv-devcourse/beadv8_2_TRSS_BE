-- 관리자 실패 거래(배송 추적 실패, TRSS-291): 송장 등록 후 오래 SHIPPED인 배송을 송장 등록 시각 최신순으로 찾는다.
-- 안전망 확인(발송 후 N일 지난 SHIPPED)도 같은 조건이라 함께 쓴다.
create index idx_delivery_shipped on delivery (shipped_at) where status = 'SHIPPED';
