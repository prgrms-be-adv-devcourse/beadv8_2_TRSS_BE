package com.backend.boundedContext.recommend.app;

import com.backend.boundedContext.recommend.domain.BeautyProfile;
import com.backend.boundedContext.recommend.domain.RecommendPolicy;
import com.backend.boundedContext.recommend.domain.RecommendProduct;
import com.backend.boundedContext.recommend.out.BeautyProfileRepository;
import com.backend.boundedContext.recommend.out.RecommendProductRepository;
import com.backend.boundedContext.recommend.out.RecommendVectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * 비어 있는 임베딩(상품·설문)을 OpenAI로 채운다. 트랜잭션 밖에서 부른다(외부 호출 동안 DB 연결을 잡지 않게).
 * 임베딩하는 동안 글이 바뀌었으면 저장하지 않고 다음 차례에 다시 만든다.
 * 묶음 호출이 실패하면 그 묶음만 한 건씩 다시 보내, 되는 건은 저장하고 실패한 건만 다음 시도 시각을 뒤로 미룬다.
 * 그래서 한 상품이 계속 실패해도 같은 묶음이 반복 실패하거나 뒤 상품이 막히지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendEmbedUseCase {
    private static final String PRODUCT = "PRODUCT";
    private static final String PROFILE = "PROFILE";
    // 한 건씩 다시 보낼 때 이만큼 연달아 실패하면 OpenAI 장애로 본다
    private static final int OUTAGE_CONSECUTIVE_FAILURES = 3;

    private final EmbeddingModel embeddingModel;
    private final RecommendPolicy recommendPolicy;
    private final RecommendProductRepository recommendProductRepository;
    private final BeautyProfileRepository beautyProfileRepository;
    private final RecommendVectorRepository recommendVectorRepository;

    public void embedPending() {
        embedProducts();
        embedProfiles();
    }

    private void embedProducts() {
        LocalDateTime now = LocalDateTime.now();
        List<Long> ids = recommendVectorRepository.findProductIdsToEmbed(recommendPolicy.getEmbeddingBatchSize(), now);
        if (ids.isEmpty()) return;

        List<RecommendProduct> products = recommendProductRepository.findAllById(ids);
        List<float[]> embeddings = embed(PRODUCT, products.stream().map(RecommendProduct::embeddingSource).toList());

        int saved = 0;
        int failed = 0;
        for (int i = 0; i < products.size(); i++) {
            RecommendProduct product = products.get(i);
            float[] embedding = embeddings.get(i);
            if (embedding == null) {
                failed++;
                recordFailure(PRODUCT, product.getId(), now,
                        () -> recommendVectorRepository.increaseProductEmbeddingAttempts(product.getId(), product.getEmbeddingSourceHash()),
                        retryAt -> recommendVectorRepository.delayProductEmbedding(product.getId(), product.getEmbeddingSourceHash(), retryAt));
            } else if (recommendVectorRepository.saveProductEmbedding(product.getId(), product.getEmbeddingSourceHash(), embedding)) {
                saved++;
            }
        }
        log.info("recommend embedding done target={} count={} saved={} failed={}", PRODUCT, products.size(), saved, failed);
    }

    private void embedProfiles() {
        LocalDateTime now = LocalDateTime.now();
        List<Long> memberIds = recommendVectorRepository.findProfileMemberIdsToEmbed(recommendPolicy.getEmbeddingBatchSize(), now);
        if (memberIds.isEmpty()) return;

        List<BeautyProfile> profiles = memberIds.stream()
                .map(beautyProfileRepository::findByMemberId).flatMap(Optional::stream).toList();
        List<float[]> embeddings = embed(PROFILE, profiles.stream().map(BeautyProfile::embeddingSource).toList());

        int saved = 0;
        int failed = 0;
        for (int i = 0; i < profiles.size(); i++) {
            BeautyProfile profile = profiles.get(i);
            float[] embedding = embeddings.get(i);
            if (embedding == null) {
                failed++;
                recordFailure(PROFILE, profile.getMemberId(), now,
                        () -> recommendVectorRepository.increaseProfileEmbeddingAttempts(profile.getMemberId(), profile.getUpdatedAt()),
                        retryAt -> recommendVectorRepository.delayProfileEmbedding(profile.getMemberId(), profile.getUpdatedAt(), retryAt));
            } else if (recommendVectorRepository.saveProfileEmbedding(profile.getMemberId(), profile.getUpdatedAt(), embedding)) {
                saved++;
            }
        }
        log.info("recommend embedding done target={} count={} saved={} failed={}", PROFILE, profiles.size(), saved, failed);
    }

    /**
     * 글 여러 개를 한 번의 호출로 임베딩한다. 실패하면 한 건씩 다시 보내 실패한 글만 가려낸다.
     * 돌려주는 목록은 texts와 같은 순서이고, 실패한 자리는 null
     */
    private List<float[]> embed(String target, List<String> texts) {
        if (texts.isEmpty()) return List.of();
        try {
            return embeddingModel.embed(texts);
        } catch (RuntimeException e) {
            log.warn("recommend embedding batch failed, retrying one by one target={} count={} reason={}", target, texts.size(), e.toString());
        }

        List<float[]> embeddings = new ArrayList<>(texts.size());
        int consecutiveFailures = 0;
        int skipped = 0;
        for (String text : texts) {
            // 연달아 실패하면 글 문제가 아니라 OpenAI 장애로 보고 나머지는 보내지 않는다(실패로 남겨 재시도 간격을 탄다)
            if (consecutiveFailures >= OUTAGE_CONSECUTIVE_FAILURES) {
                embeddings.add(null);
                skipped++;
                continue;
            }
            try {
                embeddings.add(embeddingModel.embed(text));
                consecutiveFailures = 0;
            } catch (RuntimeException e) {
                log.warn("recommend embedding failed target={} reason={}", target, e.toString());
                embeddings.add(null);
                consecutiveFailures++;
            }
        }
        if (skipped > 0) log.warn("recommend embedding looks down, skipped the rest target={} skipped={}", target, skipped);
        return embeddings;
    }

    // 실패 횟수를 늘리고 다음 시도 시각을 미룬다. 연속 실패가 기준 횟수에 처음 닿을 때 한 번만 ERROR(글 원문은 남기지 않는다)
    private void recordFailure(String target, Long id, LocalDateTime now, IntSupplier increaseAttempts, Consumer<LocalDateTime> delay) {
        int attempts = increaseAttempts.getAsInt();
        if (attempts == 0) return;   // 그사이 글·설문이 바뀌어 재시도 상태가 지워졌다
        delay.accept(now.plus(recommendPolicy.embeddingRetryDelay(attempts)));
        if (attempts == recommendPolicy.getEmbeddingFailureAlertAttempts()) {
            log.error("recommend embedding keeps failing target={} id={} attempts={}", target, id, attempts);
        }
    }
}
