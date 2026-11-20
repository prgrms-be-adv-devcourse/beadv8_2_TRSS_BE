package com.backend.boundedContext.faq.app;

import com.backend.FakeEmbeddingModelConfig;
import com.backend.FakeEmbeddingModelConfig.FakeEmbeddingModel;
import com.backend.boundedContext.faq.app.dto.FaqSearchDto;
import com.backend.boundedContext.faq.app.dto.FaqSearchDto.Item;
import com.backend.boundedContext.faq.domain.Faq;
import com.backend.boundedContext.faq.domain.FaqCategory;
import com.backend.boundedContext.faq.out.FaqRepository;
import com.backend.global.exception.DomainException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * FAQ 의미 기반 검색을 실제 DB(pgvector)로 확인함.
 * 가짜 EmbeddingModel은 질문마다 한 칸만 1인 벡터를 주므로, FAQ 임베딩을 SQL로 직접 넣어 유사도를 원하는 값으로 맞춤.
 * 정책 값은 application.yaml 그대로(상위 3건, 기준값 0.35).
 */
@SpringBootTest
class FaqSearchIntegrationTest {

    private static final String QUERY = "돈 넣은 거 돌려받고 싶어요";

    @Autowired
    private FaqFacade faqFacade;

    @Autowired
    private FaqRepository faqRepository;

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
    @DisplayName("노출 중이고 기준값 이상인 FAQ를 유사도 높은 순으로 상위 3건만 줌")
    void search() {
        Long best = saveWithSimilarity("충전 취소", true, 1.0);
        Long second = saveWithSimilarity("환불 위치", true, 0.6);
        Long third = saveWithSimilarity("충전 한도", true, 0.5);
        saveWithSimilarity("네 번째", true, 0.45);    // 기준값 이상이지만 상위 3건 밖
        saveWithSimilarity("관련 없음", true, 0.3);   // 기준값 미만
        saveWithSimilarity("숨김", false, 1.0);       // 노출 안 함

        FaqSearchDto result = faqFacade.search(QUERY);

        assertThat(result.faqs()).extracting(Item::faqId).containsExactly(best, second, third);
        Item first = result.faqs().getFirst();
        assertThat(first.similarity()).isCloseTo(1.0, within(1e-4));
        assertThat(first.category()).isEqualTo(FaqCategory.TOPUP);
        assertThat(first.categoryLabel()).isEqualTo("충전");
        assertThat(result.faqs().get(1).similarity()).isCloseTo(0.6, within(1e-4));
    }

    @Test
    @DisplayName("기준값을 넘는 FAQ가 없거나 임베딩 전이면 빈 목록")
    void noMatch() {
        saveWithSimilarity("관련 없음", true, 0.3);
        save("임베딩 전", true);

        assertThat(faqFacade.search(QUERY).faqs()).isEmpty();
    }

    @Test
    @DisplayName("질문 임베딩(OpenAI)이 실패하면 502")
    void embeddingFailure() {
        fakeEmbeddingModel.failing = true;

        assertThatThrownBy(() -> faqFacade.search(QUERY))
                .isInstanceOfSatisfying(DomainException.class,
                        e -> assertThat(e.getResultCode()).isEqualTo("502-EXTERNAL_SERVICE_ERROR"));
    }

    private Long save(String question, boolean visible) {
        Long id = faqRepository.save(Faq.create(FaqCategory.TOPUP, question, "답변", visible)).getId();
        createdIds.add(id);
        return id;
    }

    // 질문 벡터(한 칸만 1)와 코사인 유사도가 similarity가 되도록, 그 칸에 similarity, 다른 칸에 나머지 길이를 둠
    private Long saveWithSimilarity(String question, boolean visible, double similarity) {
        Long id = save(question, visible);
        int dimensions = FakeEmbeddingModelConfig.DIMENSIONS;
        int queryIndex = Math.floorMod(QUERY.hashCode(), dimensions);
        float[] v = new float[dimensions];
        v[queryIndex] = (float) similarity;
        v[(queryIndex + 1) % dimensions] = (float) Math.sqrt(1 - similarity * similarity);
        jdbcTemplate.update("update faq set embedding = cast(? as vector) where id = ?", toVector(v), id);
        return id;
    }

    private static String toVector(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
