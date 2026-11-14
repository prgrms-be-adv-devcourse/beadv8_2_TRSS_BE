package com.backend.boundedContext.payment.app;

import java.time.LocalDateTime;

// 대사 대상 충전 건의 공통 값. 다음 배치를 이어서 가져올 위치(cursor)를 만드는 데 씀
public interface TopupStaleTarget {

    Long topupId();

    LocalDateTime updatedAt();

    default TopupReconcileCursor cursor() {
        return new TopupReconcileCursor(updatedAt(), topupId());
    }
}
