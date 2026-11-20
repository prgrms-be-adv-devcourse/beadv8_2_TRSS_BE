package com.backend.boundedContext.product.out.search;

import java.util.Collection;
import java.util.List;

/**
 * 상품 검색 색인 쓰기. 검색 구현이 ES일 때는 {@link ProductEsIndexAdapter},
 * DB 검색일 때는 아무것도 하지 않는 {@link NoopProductSearchIndexAdapter}가 붙는다.
 * 실패하면 예외를 던진다. 부르는 쪽이 로그만 남기고, 색인은 다음 변경이나 전체 재색인으로 복구한다.
 */
public interface ProductSearchIndexPort {

    /** 색인을 쓰는 구현인지 (DB 검색이면 false) */
    boolean isEnabled();

    /**
     * 검색 alias가 있고, 그 인덱스의 매핑 버전이 코드의 매핑 버전 이상인지. 아니면 전체 재색인해야 한다.
     * alias가 없으면 upsert·delete는 쓰지 않고 예외를 던진다 (전체 재색인이 alias를 만든다).
     */
    boolean isCurrent();

    /** 지금 검색에 쓰는 인덱스에 문서를 넣거나 덮어쓴다. alias가 없으면 예외 */
    void upsert(List<ProductSearchDocument> documents);

    /** 지금 검색에 쓰는 인덱스에서 문서를 지운다. 없는 문서는 무시한다. alias가 없으면 예외 */
    void delete(Collection<Long> productIds);

    /** 전체 재색인용 새 인덱스를 만들고 이름을 돌려준다. 아직 검색에는 쓰지 않는다 */
    String createIndex();

    /** 전체 재색인 중인 새 인덱스에 문서를 넣는다 */
    void bulkIndex(String indexName, List<ProductSearchDocument> documents);

    /** 검색 alias를 새 인덱스로 옮기고, 이전 인덱스를 지운다 */
    void switchAlias(String indexName);

    /**
     * 상호명 없이 색인된 문서의 상품 ID를 최대 limit개 (회원 서비스가 실패해 상호명을 못 넣은 문서). 상호명을 채울 때 쓴다.
     * 색인을 쓰지 않거나 인덱스가 최신이 아니면 빈 목록
     */
    default List<Long> findIdsWithoutBusinessName(int limit) {
        return List.of();
    }
}
