-- 지급을 월 정산 Job 밖으로 뺀다. 월 정산(확정)이 정산서에 지급 예정일을 정하고, 지급 스케줄러가 그날부터 지급한다
-- 이전 정산서는 null(지급 스케줄러가 바로 지급할 수 있는 것으로 본다)
alter table settlement add column payable_on date;

-- 정산 후보를 만든 구매 확정 이벤트 ID(봉투 eventId). 어떤 이벤트에서 왔는지 따라가는 데 쓴다. 이전 후보는 null
alter table settlement_candidate add column source_event_id uuid;
