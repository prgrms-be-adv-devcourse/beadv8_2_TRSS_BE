package com.backend.global.outbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    Optional<OutboxEvent> findByEventId(UUID eventId);

    /**
     * afterId 다음의 미발행(PENDING) 이벤트 하나를 잠가서 가져온다.
     * 다른 릴레이가 잠근 행은 건너뛰므로(SKIP LOCKED) 여러 서버가 동시에 돌아도 같은 이벤트를 두 번 발행하지 않는다.
     * 같은 대상(aggregate)에 앞선 PENDING 이벤트가 있으면 가져오지 않는다. 앞 이벤트가 실패해 재시도 중이거나
     * 다른 릴레이가 처리 중이어도 뒤 이벤트가 먼저 나가지 않아, 대상별 발행 순서가 지켜진다.
     */
    @Query(value = """
            select * from outbox_event e
            where e.status = 'PENDING' and e.id > :afterId
              and not exists (
                  select 1 from outbox_event earlier
                  where earlier.aggregate_type = e.aggregate_type
                    and earlier.aggregate_id = e.aggregate_id
                    and earlier.status = 'PENDING'
                    and earlier.id < e.id
              )
            order by e.id
            limit 1
            for update skip locked
            """, nativeQuery = true)
    Optional<OutboxEvent> findNextPendingForUpdate(@Param("afterId") long afterId);

    // 실패 횟수를 올리기 전에 행을 잠근다. 다른 릴레이가 같은 이벤트를 처리 중이면 끝날 때까지 기다린다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from OutboxEvent e where e.id = :id")
    Optional<OutboxEvent> findByIdForUpdate(@Param("id") Long id);
}
