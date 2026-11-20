package com.backend.boundedContext.faq.app;

import com.backend.FakeEmbeddingModelConfig.FakeEmbeddingModel;
import com.backend.boundedContext.faq.domain.Faq;
import com.backend.boundedContext.faq.domain.FaqCategory;
import com.backend.boundedContext.faq.out.FaqRepository;
import com.backend.boundedContext.faq.out.FaqVectorRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FAQ 임베딩을 실제 DB(pgvector)로 확인함. 임베딩은 가짜 EmbeddingModel이 만듦(OpenAI 호출 없음).
 * 임베딩은 트랜잭션 밖에서 돌므로 테스트에 트랜잭션을 걸지 않음.
 * 끝나면 만든 FAQ를 지우고 V6 초기 FAQ의 임베딩을 다시 비움(초기 데이터 테스트가 임베딩 전 상태를 봄).
 */
@SpringBootTest
class FaqEmbedIntegrationTest {

    @Autowired
    private FaqFacade faqFacade;

    @Autowired
    private FaqRepository faqRepository;

    @Autowired
    private FaqVectorRepository faqVectorRepository;

    @Autowired
    private FakeEmbeddingModel fakeEmbeddingModel;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        fakeEmbeddingModel.failing = false;
        faqRepository.deleteAllById(createdIds);
        jdbcTemplate.update("update faq set embedding = null");
    }

    @Test
    @DisplayName("한 번에 배치 크기만큼 채우고, 반복하면 숨긴 FAQ까지 모두 채움")
    void embedAllPending() {
        Long hidden = save(FaqCategory.TOPUP, "숨긴 질문", "숨긴 답변", false);
        int pendingBefore = pendingCount();

        faqFacade.embedPending();
        assertThat(pendingCount()).isEqualTo(Math.max(pendingBefore - 20, 0));   // custom.faq.embeddingBatchSize

        embedUntilDone();
        assertThat(pendingCount()).isZero();
        assertThat(embedding(hidden)).isNotNull();
    }

    @Test
    @DisplayName("임베딩하는 동안 질문·답변이 바뀌었으면(해시가 다르면) 저장하지 않음")
    void staleHashNotSaved() {
        Long id = save(FaqCategory.TOPUP, "충전 한도가 있나요?", "1회 최대 100만 원이에요.", true);

        boolean saved = faqVectorRepository.saveEmbedding(id, "옛-해시", new float[1536]);

        assertThat(saved).isFalse();
        assertThat(embedding(id)).isNull();
    }

    @Test
    @DisplayName("OpenAI가 실패하면 그대로 두고, 다음 차례에 채움")
    void embeddingFailureRetried() {
        int pendingBefore = pendingCount();

        fakeEmbeddingModel.failing = true;
        faqFacade.embedPending();
        assertThat(pendingCount()).isEqualTo(pendingBefore);

        fakeEmbeddingModel.failing = false;
        embedUntilDone();
        assertThat(pendingCount()).isZero();
    }

    private Long save(FaqCategory category, String question, String answer, boolean visible) {
        Long id = faqRepository.save(Faq.create(category, question, answer, visible)).getId();
        createdIds.add(id);
        return id;
    }

    // 초기 FAQ가 배치 크기보다 많아 여러 차례에 걸쳐 채움
    private void embedUntilDone() {
        for (int i = 0; i < 10 && pendingCount() > 0; i++) faqFacade.embedPending();
    }

    private int pendingCount() {
        return jdbcTemplate.queryForObject("select count(*) from faq where embedding is null", Integer.class);
    }

    private String embedding(Long faqId) {
        return jdbcTemplate.queryForObject("select embedding::text from faq where id = ?", String.class, faqId);
    }
}
