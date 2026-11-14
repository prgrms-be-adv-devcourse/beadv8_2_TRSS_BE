package com.backend.boundedContext.payment.app;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

import com.backend.boundedContext.payment.app.dto.TopupReconcileResult;

/**
 * 대사 공통 반복: 대상 충전 건을 배치 단위로 가져와 건별로 처리
 * 배치를 다 채웠으면 마지막 건 다음부터 이어서 가져와, 장애 뒤 쌓인 건도 한 회차에 처리함
 * 처리한 건은 updatedAt이 바뀌어 대상에서 빠지고, 그대로 남은 건은 위치(cursor) 뒤부터 가져오므로 같은 회차에 다시 보지 않음
 * PG 결과를 모르는 건(UNKNOWN)이 나온 배치에서는 멈춤 (PG 장애 중에 모든 건을 조회하며 회차가 길어지지 않게)
 */
final class TopupReconcileBatches {

    private TopupReconcileBatches() {
    }

    /**
     * @param batchSize 한 번에 가져오는 건수. 가져온 건수가 이보다 적으면 마지막 배치
     * @param fetch     위치 다음부터 batchSize건을 가져옴
     * @param reconcile 한 건 처리
     * @param onError   한 건 처리 중 예상 못 한 오류 (로그). 나머지 건은 계속 처리함
     */
    static <T extends TopupStaleTarget> TopupReconcileResult run(int batchSize,
                                                                Function<TopupReconcileCursor, List<T>> fetch,
                                                                Function<T, TopupReconcileOutcome> reconcile,
                                                                BiConsumer<T, RuntimeException> onError) {
        int completed = 0, failed = 0, pending = 0, errors = 0;
        TopupReconcileCursor cursor = TopupReconcileCursor.START;
        while (true) {
            List<T> targets = fetch.apply(cursor);
            boolean pgUnknown = false;
            for (T target : targets) {
                // 건별로 잡아 한 건의 오류가 나머지 처리를 멈추지 않게 함
                try {
                    switch (reconcile.apply(target)) {
                        case COMPLETED -> completed++;
                        case FAILED -> failed++;
                        case PENDING -> pending++;
                        case UNKNOWN -> {
                            pending++;
                            pgUnknown = true;
                        }
                    }
                } catch (RuntimeException e) {
                    errors++;
                    onError.accept(target, e);
                }
            }
            if (targets.size() < batchSize || pgUnknown) {
                return new TopupReconcileResult(completed, failed, pending, errors);
            }
            cursor = targets.getLast().cursor();
        }
    }
}
