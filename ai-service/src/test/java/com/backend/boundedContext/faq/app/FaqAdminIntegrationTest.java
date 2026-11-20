package com.backend.boundedContext.faq.app;

import com.backend.boundedContext.faq.app.dto.AdminFaqDto;
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

/**
 * 관리자 FAQ 등록·수정·노출 변경·목록을 실제 DB로 확인함. 임베딩은 가짜 EmbeddingModel이 만듦(OpenAI 호출 없음).
 * 끝나면 만든 FAQ를 지우고 V6 초기 FAQ의 임베딩을 다시 비움(초기 데이터 테스트가 임베딩 전 상태를 봄).
 */
@SpringBootTest
class FaqAdminIntegrationTest {

    @Autowired
    private FaqFacade faqFacade;

    @Autowired
    private FaqRepository faqRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        faqRepository.deleteAllById(createdIds);
        jdbcTemplate.update("update faq set embedding = null");
    }

    @Test
    @DisplayName("등록: 앞뒤 공백을 지워 저장하고, 임베딩 전(반영 중)으로 시작함")
    void create() {
        Long id = create("  충전 한도가 있나요? ", " 1회 최대 100만 원이에요.\n", true);

        Faq faq = faqRepository.findById(id).orElseThrow();
        assertThat(faq.getQuestion()).isEqualTo("충전 한도가 있나요?");
        assertThat(faq.getAnswer()).isEqualTo("1회 최대 100만 원이에요.");
        assertThat(item(id).embedded()).isFalse();

        for (int i = 0; i < 10 && !item(id).embedded(); i++) faqFacade.embedPending();   // 초기 FAQ가 먼저 차례를 기다림
        assertThat(item(id).embedded()).isTrue();
    }

    @Test
    @DisplayName("수정: 질문·답변이 바뀌면 임베딩을 비우고, 카테고리만 바뀌면 그대로 둠")
    void modify() {
        Long id = createEmbedded("충전 한도가 있나요?", "1회 최대 100만 원이에요.");
        String hashBefore = faqRepository.findById(id).orElseThrow().getEmbeddingSourceHash();

        AdminFaqDto categoryOnly = faqFacade.modify(id, FaqCategory.ORDER, "충전 한도가 있나요?", "1회 최대 100만 원이에요.");
        assertThat(categoryOnly.category()).isEqualTo(FaqCategory.ORDER);
        assertThat(categoryOnly.embedded()).isTrue();

        AdminFaqDto answerChanged = faqFacade.modify(id, FaqCategory.TOPUP, "충전 한도가 있나요?", "1회 최대 200만 원이에요.");
        assertThat(answerChanged.embedded()).isFalse();
        assertThat(embedding(id)).isNull();
        assertThat(faqRepository.findById(id).orElseThrow().getEmbeddingSourceHash()).isNotEqualTo(hashBefore);
    }

    @Test
    @DisplayName("노출 변경: 노출만 바뀌고 임베딩은 그대로 둠")
    void changeVisibility() {
        Long id = createEmbedded("충전 한도가 있나요?", "1회 최대 100만 원이에요.");

        AdminFaqDto hidden = faqFacade.changeVisibility(id, false);

        assertThat(hidden.visible()).isFalse();
        assertThat(hidden.embedded()).isTrue();
        assertThat(faqRepository.findById(id).orElseThrow().isVisible()).isFalse();
    }

    @Test
    @DisplayName("목록: 숨긴 FAQ까지 최근 등록 순, 카테고리로 거를 수 있음")
    void list() {
        Long older = create("반품은 어떻게 하나요?", "배송 완료 후 신청해요.", false);
        Long newer = create("반품 거절되면요?", "결제 완료로 돌아가요.", true);

        List<AdminFaqDto> returns = faqFacade.getAdminList(FaqCategory.RETURN);
        assertThat(returns).extracting(AdminFaqDto::faqId).containsSubsequence(newer, older);
        assertThat(returns).allMatch(faq -> faq.category() == FaqCategory.RETURN);

        List<AdminFaqDto> all = faqFacade.getAdminList(null);
        assertThat(all).extracting(AdminFaqDto::category).contains(FaqCategory.MEMBER, FaqCategory.RETURN);
        assertThat(all.getFirst().faqId()).isEqualTo(newer);
    }

    @Test
    @DisplayName("없는 FAQ를 수정·노출 변경하면 404")
    void notFound() {
        assertThatThrownBy(() -> faqFacade.modify(-1L, FaqCategory.TOPUP, "질문", "답변"))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getResultCode()).isEqualTo("404-FAQ_NOT_FOUND"));
        assertThatThrownBy(() -> faqFacade.changeVisibility(-1L, false))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getResultCode()).isEqualTo("404-FAQ_NOT_FOUND"));
    }

    private Long create(String question, String answer, boolean visible) {
        FaqCategory category = question.startsWith("반품") ? FaqCategory.RETURN : FaqCategory.TOPUP;
        Long id = faqFacade.create(category, question, answer, visible);
        createdIds.add(id);
        return id;
    }

    private Long createEmbedded(String question, String answer) {
        Long id = create(question, answer, true);
        jdbcTemplate.update("update faq set embedding = cast(? as vector) where id = ?", "[" + "0,".repeat(1535) + "1]", id);
        return id;
    }

    private AdminFaqDto item(Long id) {
        return faqFacade.getAdminList(null).stream().filter(faq -> faq.faqId().equals(id)).findFirst().orElseThrow();
    }

    private String embedding(Long faqId) {
        return jdbcTemplate.queryForObject("select embedding::text from faq where id = ?", String.class, faqId);
    }
}
