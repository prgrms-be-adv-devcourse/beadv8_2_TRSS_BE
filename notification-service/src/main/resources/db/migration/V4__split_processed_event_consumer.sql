-- processed_event.consumer를 서비스 이름(notification)에서 리스너별 이름으로 나눈다
-- 기존 기록은 어느 토픽 이벤트였는지 알 수 없어 두 이름 모두로 남긴다. event_id가 이벤트마다 달라 서로 겹치지 않음
-- 배포 직후 커밋 전 메시지를 다시 받아도 이미 처리한 이벤트로 걸러진다
insert into processed_event (event_id, consumer, processed_at)
select event_id, 'notification-delivery', processed_at
  from processed_event
 where consumer = 'notification';

update processed_event
   set consumer = 'notification-order'
 where consumer = 'notification';
