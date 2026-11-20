package com.backend.boundedContext.product.out.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.json.JsonData;
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
 * <p>
 * alias가 없으면 색인에 쓰지 않고 예외를 던진다. 빈 인덱스를 만들어 붙이면 검색이 실패하지 않고 방금 색인한 몇 개만 돌려줘
 * DB 검색으로 넘어가지도 않기 때문이다 (실패보다 "틀린 결과로 성공"이 더 위험하다). alias는 전체 재색인이 만들고,
 * alias가 없으면 기동 직후와 그 뒤 주기마다 자동으로 전체 재색인한다(ProductSearchIndexRecoveryScheduler).
 * 쓰기 요청에는 require_alias를 건다. 앱이 떠 있는 동안 ES 데이터가 지워져도, ES가 매핑 없는 {@code products} 인덱스를 자동으로 만들지 않고 거부한다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "custom.product.search.engine", havingValue = "es")
public class ProductEsIndexAdapter implements ProductSearchIndexPort {
    private static final String INDEX_SETTINGS = "elasticsearch/products-index.json";
    private static final DateTimeFormatter INDEX_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final ElasticsearchClient client;
    private final String alias;
    /** 코드의 매핑 버전 (products-index.json의 mappings._meta.version). 매핑을 바꿀 때 올린다 */
    private final int mappingVersion;
    private volatile boolean aliasReady;
    /**
     * 마지막으로 확인한 인덱스가 최신인지 (isCurrent 결과, alias를 옮기면 true). 검색 어댑터가 요청마다 ES에 묻지 않고 이 값을 본다.
     * 복구 스케줄러가 주기마다 isCurrent를 불러 갱신한다. 처음 확인하기 전에는 false다
     */
    private volatile boolean knownCurrent;

    public ProductEsIndexAdapter(ElasticsearchClient client,
                                 @Value("${custom.product.search.indexAlias}") String alias) {
        this.client = client;
        this.alias = alias;
        this.mappingVersion = readMappingVersion();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    /**
     * alias가 있고 그 인덱스의 매핑 버전이 코드 이상이면 true.
     * 매핑이 dynamic: strict라서, 필드를 더한 코드가 배포되면 재색인 전까지 이전 인덱스가 새 문서를 거부한다.
     * 버전이 낮으면 복구 스케줄러가 재색인한다. 롤백한 예전 코드가 새 인덱스를 다시 만들지 않게 "낮을 때"만 재색인한다.
     */
    @Override
    public boolean isCurrent() {
        if (!aliasExists()) {
            knownCurrent = false;
            return false;
        }
        try {
            int current = client.indices().getMapping(g -> g.index(alias)).mappings().values().stream()
                    .mapToInt(index -> versionOf(index.mappings()))
                    .min().orElse(0);
            knownCurrent = current >= mappingVersion;
            return knownCurrent;
        } catch (IOException e) {
            throw new UncheckedIOException("상품 검색 인덱스 매핑을 확인하지 못했습니다. alias=" + alias, e);
        }
    }

    /**
     * 마지막으로 확인한 결과 인덱스가 최신인지 (ES에 묻지 않는다). false면 검색은 DB로 대신한다.
     * 매핑 버전이 낮은 이전 인덱스에는 새 필드가 없어, 그 필드로 거르는 검색이 오류 없이 빈 결과를 돌려주기 때문이다.
     */
    public boolean isKnownCurrent() {
        return knownCurrent;
    }

    public boolean aliasExists() {
        try {
            return client.indices().existsAlias(e -> e.name(alias)).value();
        } catch (IOException | RuntimeException e) {
            throw new SearchIndexUnavailableException("상품 검색 alias를 확인하지 못했습니다. alias=" + alias, e);
        }
    }

    /** 매핑의 _meta.version. 버전을 두기 전에 만든 인덱스는 1이다 */
    private static int versionOf(TypeMapping mappings) {
        JsonData version = mappings == null ? null : mappings.meta().get("version");
        return version == null ? 1 : version.to(Integer.class);
    }

    private static int readMappingVersion() {
        try (InputStream settings = new ClassPathResource(INDEX_SETTINGS).getInputStream()) {
            CreateIndexRequest request = CreateIndexRequest.of(c -> c.index(INDEX_SETTINGS).withJson(settings));
            return versionOf(request.mappings());
        } catch (IOException e) {
            throw new UncheckedIOException("상품 검색 인덱스 설정을 읽지 못했습니다. file=" + INDEX_SETTINGS, e);
        }
    }

    @Override
    public void upsert(List<ProductSearchDocument> documents) {
        if (documents.isEmpty()) return;
        ensureAlias();
        BulkRequest.Builder bulk = new BulkRequest.Builder().requireAlias(true);
        documents.forEach(doc -> bulk.operations(op -> op.index(i -> i
                .index(alias)
                .id(String.valueOf(doc.productId()))
                .document(doc))));
        execute(bulk.build());
    }

    @Override
    public void delete(Collection<Long> productIds) {
        if (productIds.isEmpty()) return;
        ensureAlias();

        BulkRequest.Builder bulk = new BulkRequest.Builder().requireAlias(true);
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
        // 재색인 중 인덱스가 지워졌으면 쓰지 않는다. 그대로 쓰면 ES가 같은 이름으로 매핑 없는(nori 없는) 인덱스를 자동으로 만들고,
        // 이어지는 alias 전환이 그 인덱스로 검색을 옮긴다. 이름이 있는 인덱스라 require_alias를 쓸 수 없어 묶음마다 확인한다
        if (!indexExists(indexName)) {
            throw new SearchIndexUnavailableException("재색인 중인 상품 검색 인덱스가 없어 색인하지 않습니다. index=" + indexName);
        }

        BulkRequest.Builder bulk = new BulkRequest.Builder();
        documents.forEach(doc -> bulk.operations(op -> op.index(i -> i
                .index(indexName)
                .id(String.valueOf(doc.productId()))
                .document(doc))));
        execute(bulk.build());
    }

    /**
     * 새 인덱스를 refresh한 뒤 alias를 옮긴다. bulk로 넣은 문서는 refresh 전까지 검색되지 않으므로,
     * 먼저 옮기면 잠깐 동안 검색이 오류 없이 빈 결과를 돌려준다 (DB 검색으로 넘어가지도 않는다).
     */
    @Override
    public void switchAlias(String indexName) {
        try {
            client.indices().refresh(r -> r.index(indexName));
            Set<String> previous = currentIndices();
            client.indices().updateAliases(u -> {
                u.actions(a -> a.add(add -> add.index(indexName).alias(alias)));
                previous.forEach(old -> u.actions(a -> a.remove(r -> r.index(old).alias(alias))));
                return u;
            });
            aliasReady = true;
            knownCurrent = true;   // 지금 코드의 매핑으로 만든 인덱스다
            if (!previous.isEmpty()) {
                client.indices().delete(d -> d.index(List.copyOf(previous)));
            }
            log.info("상품 검색 alias를 옮겼습니다. alias={}, index={}, 지운 인덱스={}", alias, indexName, previous);
        } catch (IOException e) {
            throw new UncheckedIOException("상품 검색 alias를 옮기지 못했습니다. index=" + indexName, e);
        }
    }

    /**
     * 상호명 필드가 없는 문서(상호명 없이 색인됨). 상호명이 빈 문자열인 문서(회원 서비스에 없는 판매자)는 필드가 있어 빠진다.
     * 인덱스가 최신이 아니면(상호명 필드가 없는 이전 매핑) 모든 문서가 걸리므로 찾지 않는다 (재색인이 채운다)
     */
    @Override
    public List<Long> findIdsWithoutBusinessName(int limit) {
        if (!knownCurrent) return List.of();
        try {
            return client.search(s -> s
                                    .index(alias)
                                    .query(q -> q.bool(b -> b.mustNot(m -> m.exists(e -> e.field("businessName")))))
                                    .size(limit)
                                    .source(src -> src.fetch(false)),
                            Void.class)
                    .hits().hits().stream()
                    .map(hit -> Long.valueOf(hit.id()))
                    .toList();
        } catch (IOException | RuntimeException e) {
            throw new SearchIndexUnavailableException("상호명이 없는 상품 검색 문서를 찾지 못했습니다. alias=" + alias, e);
        }
    }

    /**
     * alias가 없으면 쓰지 않고 예외를 던진다. 있으면 다시 묻지 않는다
     * (그 뒤에 alias가 사라지면 require_alias가 쓰기를 거부한다).
     */
    private void ensureAlias() {
        if (aliasReady) return;
        if (!aliasExists()) {
            throw new SearchIndexUnavailableException("상품 검색 alias가 없어 색인하지 않습니다. 전체 재색인이 alias를 만든 뒤 반영합니다. alias=" + alias);
        }
        aliasReady = true;
    }

    private boolean indexExists(String indexName) {
        try {
            return client.indices().exists(e -> e.index(indexName)).value();
        } catch (IOException e) {
            throw new UncheckedIOException("상품 검색 인덱스를 확인하지 못했습니다. index=" + indexName, e);
        }
    }

    private Set<String> currentIndices() throws IOException {
        if (!client.indices().existsAlias(e -> e.name(alias)).value()) return Set.of();
        return client.indices().getAlias(g -> g.name(alias)).aliases().keySet();
    }

    /**
     * bulk 요청을 보낸다. 접속·응답 실패와 문서 거부(alias가 사라짐, 이전 인덱스의 strict 매핑 등)는 모두
     * SearchIndexUnavailableException으로 던진다. ES나 인덱스가 돌아오면 같은 요청이 성공하는 실패다.
     */
    private void execute(BulkRequest request) {
        BulkResponse response;
        try {
            response = client.bulk(request);
        } catch (IOException | RuntimeException e) {
            throw new SearchIndexUnavailableException("상품 검색 색인에 접속하지 못했습니다.", e);
        }
        if (response.errors()) {
            List<String> failures = response.items().stream()
                    .filter(item -> item.error() != null)
                    .map(ProductEsIndexAdapter::describe)
                    .toList();
            if (!failures.isEmpty()) {
                throw new SearchIndexUnavailableException("상품 검색 색인 일부를 반영하지 못했습니다. " + failures);
            }
        }
    }

    private static String describe(BulkResponseItem item) {
        return item.id() + ":" + item.error().type() + ":" + item.error().reason();
    }
}
