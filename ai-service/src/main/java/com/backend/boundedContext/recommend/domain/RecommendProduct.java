package com.backend.boundedContext.recommend.domain;

import com.backend.boundedContext.recommend.domain.IngredientDictionary.IngredientMatch;
import com.backend.global.jpa.entity.BaseManualIdAndTime;
import com.backend.shared.product.event.ProductChangedEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

/**
 * AI 추천용 상품 사본. market의 상품 변경 이벤트로만 바뀐다. id = market product.id
 * 성분 태그는 전성분을 표준 성분명 사전으로 읽어 만든다(LLM 없음).
 * 임베딩(vector) 컬럼은 매핑하지 않는다. 저장·조회는 RecommendVectorRepository(JdbcTemplate)가 한다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "recommend_product")
public class RecommendProduct extends BaseManualIdAndTime {

    // 임베딩할 글에 넣는 상품 설명의 최대 글자 수
    public static final int EMBEDDING_DESCRIPTION_MAX_LENGTH = 2_000;

    @Column(nullable = false)
    private boolean beauty;

    // 전성분에서 성분명을 하나 이상 읽었는지. false면 피하는 성분을 고른 회원에게 추천하지 않는다
    @Column(nullable = false)
    private boolean ingredientsKnown;

    @Column(nullable = false)
    private boolean onSale;

    // 반영한 이벤트의 발생 시각. 더 오래된 이벤트는 버린다
    @Column(nullable = false)
    private LocalDateTime lastEventAt;

    @Column(nullable = false)
    private long price;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private BeautyCategory beautyCategory;

    @Column(nullable = false, length = 50)
    private String categoryName;

    @Column(nullable = false, length = 100)
    private String name;

    // 임베딩한 글의 SHA-256. 바뀌면 임베딩을 지우고 다시 만든다
    @Column(nullable = false, length = 64)
    private String embeddingSourceHash;

    @Column(length = 500)
    private String thumbnailUrl;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String ingredientsText;

    @Getter(AccessLevel.NONE)
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false, columnDefinition = "text[]")
    private String[] ingredientTags;

    private RecommendProduct(Long productId) {
        super(productId);
    }

    public static RecommendProduct create(ProductChangedEvent event, LocalDateTime eventAt, IngredientMatch match) {
        RecommendProduct product = new RecommendProduct(event.productId());
        product.apply(event, eventAt, match);
        return product;
    }

    /** 이벤트의 값으로 바꾼다. 이미 반영한 것보다 오래된 이벤트면 바꾸지 않고 false */
    public boolean apply(ProductChangedEvent event, LocalDateTime eventAt, IngredientMatch match) {
        if (lastEventAt != null && eventAt.isBefore(lastEventAt)) return false;

        this.lastEventAt = eventAt;
        this.beauty = event.beauty();
        this.onSale = event.onSale();
        this.price = event.price();
        this.categoryName = event.categoryName();
        this.beautyCategory = BeautyCategory.fromLabel(event.categoryName());
        this.name = event.name();
        this.description = event.description();
        this.thumbnailUrl = event.thumbnailUrl();
        this.ingredientsText = event.ingredientsText();
        applyTags(match);
        this.embeddingSourceHash = EmbeddingSource.hash(embeddingSource());
        return true;
    }

    /** 사전으로 전성분을 다시 읽는다(사전이 바뀌었을 때). 태그가 바뀌었으면 true */
    public boolean retag() {
        Set<IngredientTag> before = getIngredientTags();
        boolean knownBefore = ingredientsKnown;
        applyTags(IngredientDictionary.match(ingredientsText));
        return !before.equals(getIngredientTags()) || knownBefore != ingredientsKnown;
    }

    /** 임베딩할 글. 전성분·태그는 넣지 않는다(사전이 바뀌어도 다시 임베딩하지 않게). 설명은 앞부분만 넣는다 */
    public String embeddingSource() {
        return "카테고리: " + categoryName + "\n상품명: " + name + "\n설명: " + truncate(description, EMBEDDING_DESCRIPTION_MAX_LENGTH);
    }

    // 앞에서 maxLength자까지. 이모지처럼 두 char로 된 글자를 반으로 자르지 않는다
    static String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) return text;
        int end = Character.isHighSurrogate(text.charAt(maxLength - 1)) ? maxLength - 1 : maxLength;
        return text.substring(0, end);
    }

    public Set<IngredientTag> getIngredientTags() {
        EnumSet<IngredientTag> tags = EnumSet.noneOf(IngredientTag.class);
        Arrays.stream(ingredientTags).map(IngredientTag::valueOf).forEach(tags::add);
        return tags;
    }

    private void applyTags(IngredientMatch match) {
        this.ingredientTags = match.tags().stream().map(Enum::name).toArray(String[]::new);
        this.ingredientsKnown = match.known();
    }
}
