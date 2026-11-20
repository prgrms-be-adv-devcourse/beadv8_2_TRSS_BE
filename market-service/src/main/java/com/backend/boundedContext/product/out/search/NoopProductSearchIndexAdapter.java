package com.backend.boundedContext.product.out.search;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/** DB 검색(engine=jpa)일 때의 색인. 색인이 없으므로 아무것도 하지 않는다 */
@Component
@ConditionalOnProperty(name = "custom.product.search.engine", havingValue = "jpa")
public class NoopProductSearchIndexAdapter implements ProductSearchIndexPort {

    @Override
    public boolean isEnabled() {
        return false;
    }

    /** DB 검색에는 색인이 없으니 복구 재색인을 부르지 않도록 true */
    @Override
    public boolean isCurrent() {
        return true;
    }

    @Override
    public void upsert(List<ProductSearchDocument> documents) {
    }

    @Override
    public void delete(Collection<Long> productIds) {
    }

    @Override
    public String createIndex() {
        throw new UnsupportedOperationException("DB 검색에는 색인이 없습니다.");
    }

    @Override
    public void bulkIndex(String indexName, List<ProductSearchDocument> documents) {
        throw new UnsupportedOperationException("DB 검색에는 색인이 없습니다.");
    }

    @Override
    public void switchAlias(String indexName) {
        throw new UnsupportedOperationException("DB 검색에는 색인이 없습니다.");
    }
}
