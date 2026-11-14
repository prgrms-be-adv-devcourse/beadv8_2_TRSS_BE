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

    /** 지금 검색에 쓰는 인덱스에 문서를 넣거나 덮어쓴다 */
    void upsert(List<ProductSearchDocument> documents);

    /** 지금 검색에 쓰는 인덱스에서 문서를 지운다. 없는 문서는 무시한다 */
    void delete(Collection<Long> productIds);

    /** 전체 재색인용 새 인덱스를 만들고 이름을 돌려준다. 아직 검색에는 쓰지 않는다 */
    String createIndex();

    /** 전체 재색인 중인 새 인덱스에 문서를 넣는다 */
    void bulkIndex(String indexName, List<ProductSearchDocument> documents);

    /** 검색 alias를 새 인덱스로 옮기고, 이전 인덱스를 지운다 */
    void switchAlias(String indexName);
}
