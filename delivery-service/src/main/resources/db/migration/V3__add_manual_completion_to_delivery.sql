-- 관리자 수동 완료 기록. 택배사 조회(확인 워커)로 완료된 배송은 둘 다 null이다.
-- manual_completion_reason: 수동 완료 사유, manually_completed_at: 관리자가 처리한 시각(배송 완료 시각 delivered_at과 다름)
alter table delivery add column manual_completion_reason varchar(300);
alter table delivery add column manually_completed_at timestamp(6);
