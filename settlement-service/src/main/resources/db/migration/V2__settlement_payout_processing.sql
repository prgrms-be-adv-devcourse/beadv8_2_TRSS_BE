-- 정산 지급 3구간(① PROCESSING 선점 → ② 결제 지급 요청 → ③ 결과 기록)
-- 결제가 다른 서비스(HTTP)라 결과를 모르는 경우(응답 없음, 5xx) 정산서를 PROCESSING으로 두고,
-- 재확인 스케줄러가 이 시각 + custom.settlement.processingRecheckMinutes가 지난 건을 결제 원장(findTransfer)으로 확정한다.
-- status는 varchar(20)이고 CHECK 제약이 없어 새 값(PROCESSING)은 컬럼 변경 없이 저장된다.
alter table settlement add column processing_started_at timestamp(6);

-- 지금 PROCESSING이 관리자 재처리(MANUAL_REQUIRED에서 시작)인지. 재확인에서 지급 기록이 없으면 FAILED(자동 재시도)가 아니라
-- MANUAL_REQUIRED로 되돌린다(MANUAL_REQUIRED는 자동 재시도로 되돌리지 않는다). PROCESSING이 끝나면 false
alter table settlement add column processing_manual boolean not null default false;

-- 재확인 스케줄러 조회(status = PROCESSING and processing_started_at <= ?)
create index idx_settlement_processing on settlement (status, processing_started_at);
