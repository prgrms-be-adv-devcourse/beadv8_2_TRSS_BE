package com.backend.boundedContext.faq.in;

import com.backend.boundedContext.faq.app.FaqFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 비어 있는 FAQ 임베딩을 주기적으로 채움. custom.scheduling.enabled=false면 돌지 않음(SchedulingConfig)
 */
@Component
@RequiredArgsConstructor
public class FaqScheduler {

    private final FaqFacade faqFacade;

    @Scheduled(fixedDelayString = "${custom.faq.embeddingIntervalMillis}")
    public void embedPending() {
        faqFacade.embedPending();
    }
}
