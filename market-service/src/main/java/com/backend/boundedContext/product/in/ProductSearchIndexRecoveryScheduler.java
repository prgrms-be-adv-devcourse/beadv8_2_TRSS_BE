package com.backend.boundedContext.product.in;

import com.backend.boundedContext.product.app.ProductFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 상품 검색 인덱스가 최신이 아니면(alias가 없거나 매핑 버전이 코드보다 낮으면) 전체 재색인한다 (engine=es일 때만).
 * 기동 직후 처음 실행하고, 그 뒤로 주기마다 다시 확인한다.
 * 첫 배포, ES 데이터 재구성, 매핑을 바꾼 배포 뒤에 사람이 관리자 재색인을 부르지 않아도 검색이 채워진다.
 * 관리자 재색인 API는 매핑을 바꿀 때와 복구할 때만 쓴다.
 * <p>
 * 주기마다 확인하므로, 기동할 때 ES가 꺼져 있었거나 앱이 떠 있는 동안 ES 데이터가 지워져도 ES가 돌아온 다음 주기에 alias를 다시 만든다.
 * 최신이면 확인 요청 두 번(alias, 매핑)으로 끝난다. 재색인이 끝나기 전에는, alias가 없으면 DB 검색으로 대신하고
 * 매핑만 낮으면 이전 인덱스로 계속 검색한다 (새 필드를 쓰는 필터·정렬만 재색인 뒤에 맞는다).
 * 실패하면 ERROR로 남겨 알림이 가게 한다. 관리자 재색인이 실행 중이면 이번 주기는 건너뛴다.
 * market이 한 대라는 전제다. 여러 대가 되면 PostgreSQL advisory lock으로 한 대만 실행하게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "custom.product.search.engine", havingValue = "es")
public class ProductSearchIndexRecoveryScheduler {
    private final ProductFacade productFacade;

    @Scheduled(fixedDelayString = "${custom.product.search.aliasCheckIntervalSeconds}", timeUnit = TimeUnit.SECONDS)
    public void rebuildIfOutdated() {
        try {
            productFacade.rebuildSearchIndexIfOutdated().ifPresent(count ->
                    log.info("상품 검색 인덱스가 없거나 매핑이 바뀌어 전체 재색인했습니다. 상품 {}개", count));
        } catch (Exception e) {
            log.error("상품 검색 인덱스를 다시 만들어야 하는데 전체 재색인에 실패했습니다. 다음 주기에 다시 시도합니다. ES 상태를 확인해 주세요.", e);
        }
    }

    /** 회원 서비스가 실패해 상호명 없이 색인된 상품의 상호명을 채운다 (실패하면 다음 주기에 다시) */
    @Scheduled(fixedDelayString = "${custom.product.search.businessNameFillIntervalSeconds}", timeUnit = TimeUnit.SECONDS)
    public void fillMissingBusinessNames() {
        try {
            int count = productFacade.fillMissingBusinessNames();
            if (count > 0) log.info("상호명 없이 색인된 상품 {}개를 다시 색인했습니다.", count);
        } catch (Exception e) {
            log.warn("상호명이 빠진 상품 검색 문서를 채우지 못했습니다. 다음 주기에 다시 시도합니다.", e);
        }
    }
}
