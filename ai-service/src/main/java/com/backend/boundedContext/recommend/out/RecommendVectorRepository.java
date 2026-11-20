package com.backend.boundedContext.recommend.out;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 임베딩(pgvector vector) 컬럼 읽기·쓰기. 엔티티는 이 컬럼을 매핑하지 않는다.
 * 벡터는 '[0.1,0.2,…]' 문자열을 cast(? as vector)로 넘긴다.
 */
@Repository
@RequiredArgsConstructor
public class RecommendVectorRepository {
    private final JdbcTemplate jdbcTemplate;

    /**
     * 임베딩을 기다리는 판매 중 뷰티 상품 ID (뷰티가 아닌 상품은 추천하지 않으므로 임베딩하지 않는다).
     * 실패해서 다음 시도 시각이 아직 안 된 상품은 뺀다(한 상품이 계속 실패해도 뒤 상품이 막히지 않게)
     */
    public List<Long> findProductIdsToEmbed(int limit, LocalDateTime now) {
        return jdbcTemplate.queryForList("""
                        select id from recommend_product
                        where embedding is null and on_sale and beauty
                          and (embedding_retry_at is null or embedding_retry_at <= ?)
                        order by id limit ?
                        """,
                Long.class, now, limit);
    }

    /** 임베딩을 저장하고 재시도 상태를 지운다. 그사이 상품 글이 바뀌었으면(해시가 다르면) 저장하지 않고 false */
    public boolean saveProductEmbedding(Long productId, String sourceHash, float[] embedding) {
        return jdbcTemplate.update("""
                        update recommend_product
                        set embedding = cast(? as vector), embedding_attempts = 0, embedding_retry_at = null
                        where id = ? and embedding_source_hash = ?
                        """,
                toVector(embedding), productId, sourceHash) == 1;
    }

    /** 임베딩 실패 횟수를 1 늘리고 늘린 값을 돌려준다. 그사이 상품 글이 바뀌었으면(해시가 다르면) 세지 않고 0 */
    public int increaseProductEmbeddingAttempts(Long productId, String sourceHash) {
        List<Integer> attempts = jdbcTemplate.queryForList("""
                        update recommend_product set embedding_attempts = embedding_attempts + 1
                        where id = ? and embedding_source_hash = ?
                        returning embedding_attempts
                        """,
                Integer.class, productId, sourceHash);
        return attempts.isEmpty() ? 0 : attempts.getFirst();
    }

    /** 다음 임베딩 시도 시각을 정한다. 그 전까지 대상 조회에서 빠진다 */
    public void delayProductEmbedding(Long productId, String sourceHash, LocalDateTime retryAt) {
        jdbcTemplate.update(
                "update recommend_product set embedding_retry_at = ? where id = ? and embedding_source_hash = ?",
                retryAt, productId, sourceHash);
    }

    /** 임베딩할 글이 바뀌었을 때: 임베딩과 재시도 상태를 지워 바로 다시 만들게 한다 */
    public void clearProductEmbedding(Long productId) {
        jdbcTemplate.update(
                "update recommend_product set embedding = null, embedding_attempts = 0, embedding_retry_at = null where id = ?",
                productId);
    }

    /** 임베딩을 기다리는 설문의 회원 ID. 실패해서 다음 시도 시각이 아직 안 된 설문은 뺀다 */
    public List<Long> findProfileMemberIdsToEmbed(int limit, LocalDateTime now) {
        return jdbcTemplate.queryForList("""
                        select member_id from beauty_profile
                        where embedding is null and (embedding_retry_at is null or embedding_retry_at <= ?)
                        order by id limit ?
                        """,
                Long.class, now, limit);
    }

    /** 설문 임베딩을 저장하고 재시도 상태를 지운다. 그사이 설문을 다시 저장했으면(updated_at이 다르면) 저장하지 않고 false */
    public boolean saveProfileEmbedding(Long memberId, LocalDateTime updatedAt, float[] embedding) {
        return jdbcTemplate.update("""
                        update beauty_profile
                        set embedding = cast(? as vector), embedding_attempts = 0, embedding_retry_at = null
                        where member_id = ? and updated_at = ?
                        """,
                toVector(embedding), memberId, updatedAt) == 1;
    }

    /** 설문 임베딩 실패 횟수를 1 늘리고 늘린 값을 돌려준다. 그사이 설문을 다시 저장했으면 세지 않고 0 */
    public int increaseProfileEmbeddingAttempts(Long memberId, LocalDateTime updatedAt) {
        List<Integer> attempts = jdbcTemplate.queryForList("""
                        update beauty_profile set embedding_attempts = embedding_attempts + 1
                        where member_id = ? and updated_at = ?
                        returning embedding_attempts
                        """,
                Integer.class, memberId, updatedAt);
        return attempts.isEmpty() ? 0 : attempts.getFirst();
    }

    /** 설문의 다음 임베딩 시도 시각을 정한다. 그 전까지 대상 조회에서 빠진다 */
    public void delayProfileEmbedding(Long memberId, LocalDateTime updatedAt, LocalDateTime retryAt) {
        jdbcTemplate.update(
                "update beauty_profile set embedding_retry_at = ? where member_id = ? and updated_at = ?",
                retryAt, memberId, updatedAt);
    }

    /** 설문을 다시 저장했을 때: 임베딩과 재시도 상태를 지워 바로 다시 만들게 한다 */
    public void clearProfileEmbedding(Long memberId) {
        jdbcTemplate.update(
                "update beauty_profile set embedding = null, embedding_attempts = 0, embedding_retry_at = null where member_id = ?",
                memberId);
    }

    /** 추천 결과 한 줄. ingredientTags는 코드 이름 */
    public record RecommendedRow(Long productId, String name, long price, String thumbnailUrl, String categoryName,
                                 String beautyCategory, List<String> ingredientTags) {
    }

    /**
     * 설문 기반 추천. SQL 한 번으로 거르고 정렬한다.
     * - 거르기(WHERE, 점수와 무관): 판매 중 뷰티 상품만. 피하는 성분을 골랐으면 그 태그가 하나라도 있는 상품과 전성분을 모르는 상품을 뺀다
     * - 정렬: 프로필과 상품 임베딩의 코사인 유사도 + 겹친 선호 성분 가점 + 관심 카테고리 가점.
     *   프로필이나 상품 임베딩이 아직 없으면 유사도는 0으로 보고 가점·최신순으로 정한다
     */
    public List<RecommendedRow> findRecommended(Long memberId, List<String> preferred, List<String> avoided,
                                                List<String> interests, double preferredWeight, double categoryWeight, int size) {
        return jdbcTemplate.query("""
                        with me as (select embedding from beauty_profile where member_id = ?)
                        select p.id, p.name, p.price, p.thumbnail_url, p.category_name, p.beauty_category, p.ingredient_tags,
                               coalesce(1 - (p.embedding <=> (select embedding from me)), 0)
                                 + ? * cardinality(array(select unnest(p.ingredient_tags) intersect select unnest(cast(? as text[]))))
                                 + ? * (case when p.beauty_category = any(cast(? as text[])) then 1 else 0 end) as score
                        from recommend_product p
                        where p.on_sale and p.beauty
                          and (cardinality(cast(? as text[])) = 0
                               or (p.ingredients_known and not (p.ingredient_tags && cast(? as text[]))))
                        order by score desc, p.id desc
                        limit ?
                        """,
                (rs, i) -> toRow(rs),
                memberId, preferredWeight, toArray(preferred), categoryWeight, toArray(interests),
                toArray(avoided), toArray(avoided), size);
    }

    /** 설문이 없거나 비회원이면: 판매 중 뷰티 상품 최신순 */
    public List<RecommendedRow> findLatest(int size) {
        return jdbcTemplate.query("""
                        select id, name, price, thumbnail_url, category_name, beauty_category, ingredient_tags
                        from recommend_product where on_sale and beauty order by id desc limit ?
                        """,
                (rs, i) -> toRow(rs), size);
    }

    private static RecommendedRow toRow(ResultSet rs) throws SQLException {
        return new RecommendedRow(rs.getLong("id"), rs.getString("name"), rs.getLong("price"),
                rs.getString("thumbnail_url"), rs.getString("category_name"), rs.getString("beauty_category"),
                List.of((String[]) rs.getArray("ingredient_tags").getArray()));
    }

    // 코드 이름만 들어가므로(enum) 따옴표·쉼표 이스케이프가 필요 없다
    private static String toArray(List<String> codes) {
        return "{" + String.join(",", codes) + "}";
    }

    static String toVector(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }
}
