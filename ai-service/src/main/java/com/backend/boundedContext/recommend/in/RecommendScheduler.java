package com.backend.boundedContext.recommend.in;

import com.backend.boundedContext.recommend.app.RecommendFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

/**
 * 추천 데이터를 맞추는 작업.
 * - 기동할 때: 성분 사전이 바뀌었을 수 있으므로 모든 상품의 태그를 다시 계산한다(문자열 처리만, OpenAI 호출 없음)
 * - 주기적으로: 비어 있는 임베딩(상품·설문)을 채운다. custom.scheduling.enabled=false면 돌지 않는다(SchedulingConfig)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecommendScheduler implements ApplicationRunner {
    private final RecommendFacade recommendFacade;

    @Override
    public void run(ApplicationArguments args) {
        int changed = recommendFacade.retagAll();
        log.info("recommend retag done changed={}", changed);
    }

    @Scheduled(fixedDelayString = "${custom.recommend.embeddingIntervalMillis}")
    public void embedPending() {
        recommendFacade.embedPending();
    }
}
