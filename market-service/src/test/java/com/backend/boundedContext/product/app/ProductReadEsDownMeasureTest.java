package com.backend.boundedContext.product.app;

import com.backend.boundedContext.product.out.search.ProductEsIndexAdapter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ES가 멈췄을 때 검색·비슷한 상품 응답 시간 측정. 실행: {@code ./gradlew :market-service:measureTest}.
 * <ul>
 *   <li>ES 주소를 접속은 받지만 응답하지 않는 로컬 소켓으로 둔다. ES가 과부하·GC로 멈췄을 때처럼 응답 제한 시간(3초)까지 기다린 뒤 실패한다.</li>
 *   <li>운영에서는 ES가 살아 있을 때 확인한 {@code knownCurrent}가 ES가 멈춘 뒤에도 true로 남는다. 그 상태를 직접 맞춘다.</li>
 *   <li>이 설정에서만 뜨는 검색 색인 컨슈머는 켜지 않는다. 로컬 Kafka의 product.events를 가져가 멈춘 ES에 색인하려 하지 않게 한다.</li>
 * </ul>
 */
@Tag("measure")
@SpringBootTest(properties = "custom.product.search.engine=es")
class ProductReadEsDownMeasureTest extends ProductReadMeasureSupport {
    private static final int ROUNDS_ES_DOWN = 10;
    /** 호출 한 번을 기다리는 최대 시간. 응답 제한(3초)이 걸리면 그 안에 끝난다 */
    private static final int CAP_SECONDS = 15;

    /** 접속은 받고(커널 backlog) 응답은 하지 않는 서버. accept도 하지 않는다 */
    private static final ServerSocket HUNG_ES = open();

    @Autowired
    private ProductEsIndexAdapter indexAdapter;

    @DynamicPropertySource
    static void hungEs(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", () -> "http://localhost:" + HUNG_ES.getLocalPort());
    }

    @AfterAll
    static void closeHungEs() throws IOException {
        HUNG_ES.close();
    }

    @Test
    @DisplayName("ES가 응답하지 않을 때 검색·비슷한 상품 응답 시간 (요청마다 ES를 기다리는지)")
    void searchWhileEsDown() {
        ReflectionTestUtils.setField(indexAdapter, "knownCurrent", true);

        record("ES 응답 없음: 검색 응답 시간", cappedLatencies(this::search));
        record("ES 응답 없음: 비슷한 상품 응답 시간",
                cappedLatencies(() -> productFacade.getSimilarProducts(detailProductId, 10)));
    }

    /**
     * 한 번에 {@value #CAP_SECONDS}초까지만 기다리며 {@value #ROUNDS_ES_DOWN}번 잰다.
     * ES 클라이언트에 응답 제한이 없으면 요청이 끝나지 않으므로, 넘으면 그 사실만 적고 멈춘다.
     * 끝나지 않은 호출은 데몬 스레드에 남겨 두고 테스트 JVM이 끝날 때 함께 정리된다.
     */
    private String cappedLatencies(Runnable action) {
        List<Double> times = new ArrayList<>();
        for (int i = 0; i < ROUNDS_ES_DOWN; i++) {
            FutureTask<Double> call = new FutureTask<>(() -> timed(action));
            Thread.ofPlatform().daemon().start(call);
            try {
                times.add(call.get(CAP_SECONDS, TimeUnit.SECONDS));
            } catch (TimeoutException e) {
                return CAP_SECONDS + "초가 지나도 응답·예외 없음 (요청이 끝나지 않음, " + (i + 1) + "번째 호출)";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (ExecutionException e) {
                throw new IllegalStateException(e.getCause());
            }
        }
        times.sort(null);
        return summary(times);
    }

    private static ServerSocket open() {
        try {
            return new ServerSocket(0, 100);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @TestConfiguration
    static class SearchIndexConsumerOff {
        /** ProductSearchIndexKafkaConfig.LISTENER_FACTORY (패키지 밖이라 이름을 그대로 쓴다) */
        private static final String SEARCH_INDEX_LISTENER_FACTORY = "productSearchIndexListenerFactory";

        @Bean
        static BeanPostProcessor searchIndexConsumerOff() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (SEARCH_INDEX_LISTENER_FACTORY.equals(beanName) && bean instanceof AbstractKafkaListenerContainerFactory<?, ?, ?> factory) {
                        factory.setAutoStartup(false);
                    }
                    return bean;
                }
            };
        }
    }
}
