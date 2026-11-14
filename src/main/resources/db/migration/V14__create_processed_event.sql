-- Kafka 컨슈머가 처리한 이벤트 기록. 같은 이벤트를 두 번 받아도 한 번만 처리하게 한다.
-- Kafka·Outbox는 최소 한 번(at-least-once) 전달이라 재시도·리밸런스로 같은 메시지가 다시 올 수 있다.
-- 컨슈머마다 따로 기록한다(같은 이벤트를 정산과 결제가 각각 한 번씩 처리).

create table processed_event (
    event_id uuid not null,
    consumer varchar(100) not null,
    processed_at timestamp(6) not null,
    primary key (event_id, consumer)
);
