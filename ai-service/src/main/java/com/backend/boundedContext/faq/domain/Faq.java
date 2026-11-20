package com.backend.boundedContext.faq.domain;

import com.backend.global.jpa.entity.BaseIdAndTime;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 챗봇이 답변 근거로 쓰는 FAQ. 한 건이 벡터 검색의 문서 하나임.
 * 임베딩(vector) 컬럼은 엔티티 필드로 두지 않음(JPA가 읽고 쓰지 않음). 저장·조회는 FaqVectorRepository(JdbcTemplate)가 함.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "faq")
public class Faq extends BaseIdAndTime {

    // false면 챗봇 검색에서 빠짐
    @Column(nullable = false)
    private boolean visible;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FaqCategory category;

    // 임베딩한 글의 SHA-256. 바뀌면 임베딩을 지우고 다시 만듦
    @Column(nullable = false, length = 64)
    private String embeddingSourceHash;

    @Column(nullable = false, length = 200)
    private String question;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String answer;

    public static Faq create(FaqCategory category, String question, String answer, boolean visible) {
        Faq faq = new Faq();
        faq.category = category;
        faq.question = question.strip();
        faq.answer = answer.strip();
        faq.visible = visible;
        faq.embeddingSourceHash = hash(faq.embeddingSource());
        return faq;
    }

    // 카테고리·질문·답변을 바꿈. 임베딩할 글(질문·답변)이 바뀌었으면 true: 호출한 쪽이 임베딩을 비워야 함
    public boolean modify(FaqCategory category, String question, String answer) {
        this.category = category;
        this.question = question.strip();
        this.answer = answer.strip();

        String newHash = hash(embeddingSource());
        if (newHash.equals(embeddingSourceHash)) return false;
        this.embeddingSourceHash = newHash;

        return true;
    }

    // 노출 여부만 바꿈. 임베딩은 그대로 둠(다시 노출하면 바로 검색됨)
    public void changeVisibility(boolean visible) {
        this.visible = visible;
    }

    // 임베딩할 글. 카테고리는 넣지 않음(검색 필터로만 씀). V6의 해시 계산과 같은 형식이어야 함
    public String embeddingSource() {
        return question + "\n" + answer;
    }

    // 글의 SHA-256 hex. 저장된 해시와 다르면 글이 바뀐 것으로 보고 다시 임베딩함(V6의 sha256 계산과 결과가 같음)
    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // 모든 JVM이 SHA-256을 지원함
        }
    }
}
