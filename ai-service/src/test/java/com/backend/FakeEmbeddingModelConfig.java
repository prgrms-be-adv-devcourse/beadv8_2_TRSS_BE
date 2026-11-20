package com.backend;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * 테스트는 실제 OpenAI를 부르지 않는다. 글마다 같은 값이 나오는 1536차원 가짜 벡터를 돌려준다.
 * failing을 켜면 예외를 던져 OpenAI 장애를 흉내 낸다.
 * failingText에 맞는 글이 요청에 하나라도 있으면 그 요청만 실패한다(특정 글이 계속 실패하는 경우를 흉내 낸다).
 */
@Configuration
public class FakeEmbeddingModelConfig {

    public static final int DIMENSIONS = 1536;

    @Bean
    @Primary
    public FakeEmbeddingModel fakeEmbeddingModel() {
        return new FakeEmbeddingModel();
    }

    public static class FakeEmbeddingModel implements EmbeddingModel {
        public volatile boolean failing;
        public volatile Predicate<String> failingText = text -> false;
        public final AtomicInteger calls = new AtomicInteger();
        public final List<String> requestedTexts = new CopyOnWriteArrayList<>();

        /** 테스트가 바꾼 상태를 되돌린다 */
        public void reset() {
            failing = false;
            failingText = text -> false;
            requestedTexts.clear();
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            calls.incrementAndGet();
            requestedTexts.addAll(request.getInstructions());
            if (failing) throw new IllegalStateException("가짜 OpenAI 장애");
            if (request.getInstructions().stream().anyMatch(failingText)) throw new IllegalStateException("가짜 OpenAI 입력 거절");
            List<Embedding> embeddings = new ArrayList<>();
            for (int i = 0; i < request.getInstructions().size(); i++) {
                embeddings.add(new Embedding(vector(request.getInstructions().get(i)), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(Document document) {
            return vector(document.getText());
        }

        static float[] vector(String text) {
            float[] v = new float[DIMENSIONS];
            v[Math.floorMod(text.hashCode(), DIMENSIONS)] = 1f;
            return v;
        }
    }
}
