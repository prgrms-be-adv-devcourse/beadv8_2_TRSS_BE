package com.backend.global.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    Optional<OutboxEvent> findByEventId(UUID eventId);

    /**
     * afterId 다음의 미발행(PENDING) 이벤트 하나를 잠가서 가져온다.
     * 다른 릴레이가 잠근 행은 건너뛰므로(SKIP LOCKED) 여러 서버가 동시에 돌아도 같은 이벤트를 두 번 발행하지 않는다.
     */
    @Query(value = """
            select * from outbox_event
            where status = 'PENDING' and id > :afterId
            order by id
            limit 1
            for update skip locked
            """, nativeQuery = true)
    Optional<OutboxEvent> findNextPendingForUpdate(@Param("afterId") long afterId);
}
