package com.backend.boundedContext.product.out.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * ES 상품 색인. 검색과 변경 반영은 alias({@code products})로 하고, 실제 인덱스는 {@code products_{만든 시각}}이다.
 * 매핑을 바꿀 때는 전체 재색인으로 새 인덱스를 채운 뒤 alias만 옮긴다.
 * alias가 아직 없을 때 쓰면 ES가 매핑 없이 {@code products} 인덱스를 자동으로 만들어 버리므로, 쓰기 전에 빈 인덱스와 alias를 먼저 만든다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "custom.product.search.engine", havingValue = "es")
public class ProductEsIndexAdapter implements ProductSearchIndexPort {
    private static final String INDEX_SETTINGS = "elasticsearch/products-index.json";
    private static final DateTimeFormatter INDEX_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final ElasticsearchClient client;
    private final String alias;
    private volatile boolean aliasReady;

    public ProductEsIndexAdapter(ElasticsearchClient client,
                                 @Value("${custom.product.search.indexAlias}") String alias) {
        this.client = client;
        this.alias = alias;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void upsert(List<ProductSearchDocument> documents) {
        if (documents.isEmpty()) return;
        ensureAlias();
        bulkIndex(alias, documents);
    }

    @Override
    public void delete(Collection<Long> productIds) {
        if (productIds.isEmpty()) return;
        ensureAlias();

        BulkRequest.Builder bulk = new BulkRequest.Builder();
        productIds.forEach(id -> bulk.operations(op -> op.delete(d -> d.index(alias).id(String.valueOf(id)))));
        execute(bulk.build());
    }

    @Override
    public String createIndex() {
        String indexName = alias + "_" + LocalDateTime.now().format(INDEX_SUFFIX);
        try (InputStream settings = new ClassPathResource(INDEX_SETTINGS).getInputStream()) {
            client.indices().create(c -> c.index(indexName).withJson(settings));
        } catch (IOException e) {
            throw new UncheckedIOException("상품 검색 인덱스를 만들지 못했습니다. index=" + indexName, e);
        }
        return indexName;
    }

    @Override
    public void bulkIndex(String indexName, List<ProductSearchDocument> documents) {
        if (documents.isEmpty()) return;

        BulkRequest.Builder bulk = new BulkRequest.Builder();
        documents.forEach(doc -> bulk.operations(op -> op.index(i -> i
                .index(indexName)
                .id(String.valueOf(doc.productId()))
                .document(doc))));
        execute(bulk.build());
    }

    @Override
    public void switchAlias(String indexName) {
        try {
            Set<String> previous = currentIndices();
            client.indices().updateAliases(u -> {
                u.actions(a -> a.add(add -> add.index(indexName).alias(alias)));
                previous.forEach(old -> u.actions(a -> a.remove(r -> r.index(old).alias(alias))));
                return u;
            });
            aliasReady = true;
            if (!previous.isEmpty()) {
                client.indices().delete(d -> d.index(List.copyOf(previous)));
            }
            log.info("상품 검색 alias를 옮겼습니다. alias={}, index={}, 지운 인덱스={}", alias, indexName, previous);
        } catch (IOException e) {
            throw new UncheckedIOException("상품 검색 alias를 옮기지 못했습니다. index=" + indexName, e);
        }
    }

    /** alias가 없으면 빈 인덱스를 만들어 붙인다. 한 번 확인하면 다시 묻지 않는다 */
    private void ensureAlias() {
        if (aliasReady) return;
        synchronized (this) {
            if (aliasReady) return;
            try {
                if (!client.indices().existsAlias(e -> e.name(alias)).value()) {
                    switchAlias(createIndex());
                    log.info("상품 검색 인덱스가 없어 빈 인덱스를 만들었습니다. 기존 상품은 전체 재색인으로 채웁니다.");
                }
                aliasReady = true;
            } catch (IOException e) {
                throw new UncheckedIOException("상품 검색 alias를 확인하지 못했습니다. alias=" + alias, e);
            }
        }
    }

    private Set<String> currentIndices() throws IOException {
        if (!client.indices().existsAlias(e -> e.name(alias)).value()) return Set.of();
        return client.indices().getAlias(g -> g.name(alias)).aliases().keySet();
    }

    private void execute(BulkRequest request) {
        try {
            BulkResponse response = client.bulk(request);
            if (response.errors()) {
                List<String> failures = response.items().stream()
                        .filter(item -> item.error() != null)
                        .map(ProductEsIndexAdapter::describe)
                        .toList();
                if (!failures.isEmpty()) {
                    throw new IllegalStateException("상품 검색 색인 일부를 반영하지 못했습니다. " + failures);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("상품 검색 색인에 접속하지 못했습니다.", e);
        }
    }

    private static String describe(BulkResponseItem item) {
        return item.id() + ":" + item.error().type() + ":" + item.error().reason();
    }
}
