package com.backend.boundedContext.product.out.search;

import com.backend.boundedContext.product.domain.ProductPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ES 검색(검색·비슷한 상품·추천)을 지금 해도 되는지 판단한다. 세 검색 어댑터가 같이 쓴다.
 *
 * <p>ES 접속·응답이 실패하면 정해 둔 시간(skipAfterFailureSeconds) 동안 ES를 부르지 않고 바로 DB로 대신하게 한다.
 * 그러지 않으면 ES가 죽은 동안 모든 요청이 응답 제한 시간(3초)만큼 기다린 뒤에야 DB로 넘어간다.
 * 시간이 지나면 다음 요청이 ES를 다시 불러 본다. 성공하면 건너뛰기를 끝내고, 또 실패하면 다시 건너뛴다.
 * <ul>
 *   <li>같은 ES를 쓰므로 하나를 같이 쓴다. 따로 두면 검색이 장애를 알아도 비슷한 상품·추천이 각자 한 번씩 더 기다린다.</li>
 *   <li>접속·응답 실패(원인에 {@link IOException})에만 건다. 질의 오류는 ES가 살아 있다는 뜻이라,
 *       코드 버그 하나로 모든 검색이 DB로 넘어가지 않게 건너뛰지 않는다.</li>
 *   <li>색인(쓰기)은 이 판단을 쓰지 않는다. 건너뛰면 그 변경이 색인에서 영영 빠지므로, 일시 오류로 다시 처리한다.</li>
 *   <li>로그는 건너뛰기를 시작할 때와 복구됐을 때만 남긴다. 장애 동안 요청마다 남기면 로그가 장애를 가린다.</li>
 *   <li>{@link ProductEsIndexAdapter#isKnownCurrent()}는 "매핑 버전이 맞는가"를 뜻해 따로 둔다. "ES가 살아 있는가"는 여기서만 본다.</li>
 * </ul>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "custom.product.search.engine", havingValue = "es")
public class ProductEsSearchGuard {
    private final ProductEsIndexAdapter indexAdapter;
    private final Duration skipAfterFailure;
    private final Clock clock;

    /** 이 시각 전까지 ES를 부르지 않는다. 건너뛰지 않으면 null */
    private final AtomicReference<Instant> skipUntil = new AtomicReference<>();

    @Autowired
    public ProductEsSearchGuard(ProductEsIndexAdapter indexAdapter, ProductPolicy productPolicy) {
        this(indexAdapter, Duration.ofSeconds(productPolicy.getSearchSkipAfterFailureSeconds()), Clock.systemDefaultZone());
    }

    /** 테스트가 시간을 돌리려고 Clock을 넣는다 */
    ProductEsSearchGuard(ProductEsIndexAdapter indexAdapter, Duration skipAfterFailure, Clock clock) {
        this.indexAdapter = indexAdapter;
        this.skipAfterFailure = skipAfterFailure;
        this.clock = clock;
    }

    /** 인덱스가 최신이고(매핑 버전) 접속·응답 실패로 건너뛰는 중이 아니면 true. false면 어댑터는 DB로 대신한다 */
    public boolean canSearch() {
        if (!indexAdapter.isKnownCurrent()) return false;
        Instant until = skipUntil.get();
        return until == null || !clock.instant().isBefore(until);
    }

    /** ES 검색이 실패했을 때. 접속·응답 실패면 건너뛰기를 시작(또는 연장)하고, 질의 오류면 아무것도 하지 않는다 */
    public void onFailure(Exception e) {
        if (!isUnavailable(e)) return;
        Instant until = clock.instant().plus(skipAfterFailure);
        if (skipUntil.getAndSet(until) == null) {
            log.warn("ES에 접속·응답하지 못해 {}초 동안 ES 검색을 건너뛰고 DB로 대신합니다. 그 뒤 다시 시도합니다. cause={}",
                    skipAfterFailure.toSeconds(), e.toString());
        }
    }

    /** ES 검색이 성공했을 때. 건너뛰다가 다시 성공했으면 건너뛰기를 끝낸다 */
    public void onSuccess() {
        if (skipUntil.get() != null && skipUntil.getAndSet(null) != null) {
            log.info("ES 검색이 다시 성공해 ES 검색을 재개합니다.");
        }
    }

    /** 원인 사슬에 IOException(접속 거부·응답 시간 초과·연결 끊김 등)이 있으면 ES에 닿지 못한 실패다 */
    static boolean isUnavailable(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException) return true;
            if (cause.getCause() == cause) break;
        }
        return false;
    }
}
