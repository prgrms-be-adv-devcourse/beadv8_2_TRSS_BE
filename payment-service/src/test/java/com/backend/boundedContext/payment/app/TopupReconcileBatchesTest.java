package com.backend.boundedContext.payment.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backend.boundedContext.payment.app.dto.TopupReconcileResult;

class TopupReconcileBatchesTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 14, 9, 0);

    private record Target(Long topupId, LocalDateTime updatedAt) implements TopupStaleTarget {}

    // 요청받은 위치(cursor)를 기록하고, 위치별로 정해 둔 배치를 돌려주는 가짜 조회
    private final List<TopupReconcileCursor> requested = new ArrayList<>();

    @Test
    @DisplayName("배치를 다 채우면 마지막 건 다음 위치부터 이어서 가져오고, 덜 채운 배치에서 멈춘다")
    void continuesWhileFull() {
        Target t1 = target(1), t2 = target(2), t3 = target(3);
        Map<TopupReconcileCursor, List<Target>> batches = Map.of(
                TopupReconcileCursor.START, List.of(t1, t2),
                t2.cursor(), List.of(t3));

        TopupReconcileResult result = TopupReconcileBatches.run(2, fetch(batches), t -> TopupReconcileOutcome.COMPLETED, (t, e) -> {});

        assertThat(requested).containsExactly(TopupReconcileCursor.START, t2.cursor());
        assertThat(result.completed()).isEqualTo(3);
    }

    @Test
    @DisplayName("마지막 배치가 정확히 다 차면 빈 배치를 한 번 더 확인하고 멈춘다")
    void stopsOnEmptyBatch() {
        Target t1 = target(1), t2 = target(2);
        Map<TopupReconcileCursor, List<Target>> batches = Map.of(TopupReconcileCursor.START, List.of(t1, t2));

        TopupReconcileResult result = TopupReconcileBatches.run(2, fetch(batches), t -> TopupReconcileOutcome.FAILED, (t, e) -> {});

        assertThat(requested).containsExactly(TopupReconcileCursor.START, t2.cursor());
        assertThat(result.failed()).isEqualTo(2);
    }

    @Test
    @DisplayName("PG 결과를 모르는 건(UNKNOWN)이 나온 배치는 다 처리하되, 다음 배치는 가져오지 않는다")
    void stopsOnUnknown() {
        Target t1 = target(1), t2 = target(2), t3 = target(3);
        Map<TopupReconcileCursor, List<Target>> batches = Map.of(
                TopupReconcileCursor.START, List.of(t1, t2),
                t2.cursor(), List.of(t3));

        TopupReconcileResult result = TopupReconcileBatches.run(2, fetch(batches),
                t -> t == t1 ? TopupReconcileOutcome.UNKNOWN : TopupReconcileOutcome.COMPLETED, (t, e) -> {});

        assertThat(requested).containsExactly(TopupReconcileCursor.START);
        assertThat(result.pending()).isEqualTo(1);
        assertThat(result.completed()).isEqualTo(1);
    }

    @Test
    @DisplayName("결과를 정하지 못한 건(PENDING)이나 처리 중 오류가 있어도 다음 배치를 이어서 가져온다")
    void continuesOnPendingAndError() {
        Target t1 = target(1), t2 = target(2), t3 = target(3);
        Map<TopupReconcileCursor, List<Target>> batches = Map.of(
                TopupReconcileCursor.START, List.of(t1, t2),
                t2.cursor(), List.of(t3));
        List<Target> errored = new ArrayList<>();

        TopupReconcileResult result = TopupReconcileBatches.run(2, fetch(batches), t -> {
            if (t == t2) throw new IllegalStateException("반영 실패");
            return t == t1 ? TopupReconcileOutcome.PENDING : TopupReconcileOutcome.COMPLETED;
        }, (t, e) -> errored.add(t));

        assertThat(requested).containsExactly(TopupReconcileCursor.START, t2.cursor());
        assertThat(errored).containsExactly(t2);
        assertThat(result).isEqualTo(new TopupReconcileResult(1, 0, 1, 1));
    }

    private java.util.function.Function<TopupReconcileCursor, List<Target>> fetch(Map<TopupReconcileCursor, List<Target>> batches) {
        return cursor -> {
            requested.add(cursor);
            return batches.getOrDefault(cursor, List.of());
        };
    }

    private static Target target(long id) {
        return new Target(id, BASE.plusMinutes(id));
    }
}
