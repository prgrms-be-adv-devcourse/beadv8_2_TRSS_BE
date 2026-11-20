-- 수동 확인 표시. 대사가 자동으로 정할 수 없는 CANCELING·METHOD_CANCELING 충전 건에 사유와 표시 시각을 남긴다
-- 표시된 건은 대사·오래 남은 건 경고에서 빠지고, 관리자가 정리하면 지운다
alter table topup add column manual_check_reason varchar(40);
alter table topup add column manual_check_at timestamp(6);
