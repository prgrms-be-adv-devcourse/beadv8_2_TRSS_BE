package com.backend.boundedContext.chat.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatCitationTest {

    @Test
    @DisplayName("끝의 [근거: 1, 3]을 떼고 번호를 읽음. 중복은 한 번만")
    void numbers() {
        ChatCitation citation = ChatCitation.parse("배송비는 2,500원이에요.\n[근거: 3, 1, 3]", 3);

        assertThat(citation.answer()).isEqualTo("배송비는 2,500원이에요.");
        assertThat(citation.faqNumbers()).containsExactly(3, 1);
    }

    @Test
    @DisplayName("[근거: 없음]이면 빈 목록(FAQ로 답하지 못함)")
    void none() {
        ChatCitation citation = ChatCitation.parse("FAQ에 없는 내용이에요. 고객센터로 문의해 주세요.\n[근거: 없음]  ", 3);

        assertThat(citation.answer()).isEqualTo("FAQ에 없는 내용이에요. 고객센터로 문의해 주세요.");
        assertThat(citation.faqNumbers()).isEmpty();
    }

    @Test
    @DisplayName("표시가 없으면 답변 그대로, 번호는 null")
    void missing() {
        ChatCitation citation = ChatCitation.parse("  배송비는 2,500원이에요.  ", 3);

        assertThat(citation.answer()).isEqualTo("배송비는 2,500원이에요.");
        assertThat(citation.faqNumbers()).isNull();
    }

    @Test
    @DisplayName("범위 밖 번호나 숫자가 아닌 값이 있으면 표시는 떼되 번호는 null(읽을 수 없음)")
    void unreadable() {
        assertThat(ChatCitation.parse("답변\n[근거: 4]", 3).faqNumbers()).isNull();
        assertThat(ChatCitation.parse("답변\n[근거: 0]", 3).faqNumbers()).isNull();
        assertThat(ChatCitation.parse("답변\n[근거: 일]", 3).faqNumbers()).isNull();
        assertThat(ChatCitation.parse("답변\n[근거: 일]", 3).answer()).isEqualTo("답변");
    }

    @Test
    @DisplayName("답변 중간의 [근거: …]는 표시로 보지 않음(맨 끝만)")
    void onlyAtEnd() {
        ChatCitation citation = ChatCitation.parse("[근거: 1] 이건 본문이에요.", 3);

        assertThat(citation.answer()).isEqualTo("[근거: 1] 이건 본문이에요.");
        assertThat(citation.faqNumbers()).isNull();
    }
}
