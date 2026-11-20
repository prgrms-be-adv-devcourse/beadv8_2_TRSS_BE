package com.backend.boundedContext.product.in;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * 검색 색인 컨슈머(ProductSearchIndexKafkaListener) 전용 리스너 팩토리 (engine=es일 때만).
 * <p>
 * 메시지를 묶음으로 받는다(batch listener, 묶음을 처리한 뒤 offset 커밋). 리스너는 일시 오류만 밖으로 던지고
 * 그 밖의 오류는 상품 단위로 건너뛰므로(ProductSearchIndexKafkaListener), 여기 오는 실패는 모두 시간이 지나면 성공한다.
 * 공통 실패 처리(KafkaConsumerConfig: 1·2·4·8초 뒤 DLT)를 쓰면 ES 장애가 15초만 넘어도 DLT로 가서 수동 복구가 다시 필요하므로,
 * 성공할 때까지 같은 묶음을 다시 처리한다 (간격은 retryInitialIntervalMillis부터 두 배씩, 최대 retryMaxIntervalMillis).
 * 색인은 DB를 다시 읽어 쓰는 일이라 몇 번을 다시 해도 결과가 같다. 이 그룹의 offset만 멈추므로 다른 컨슈머는 영향이 없다.
 * 나머지 설정은 스프링 부트 기본 팩토리와 같다.
 */
@Configuration
@ConditionalOnProperty(name = "custom.product.search.engine", havingValue = "es")
public class ProductSearchIndexKafkaConfig {

    static final String LISTENER_FACTORY = "productSearchIndexListenerFactory";

    @Bean(name = LISTENER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<Object, Object> productSearchIndexListenerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            @Value("${custom.product.search.indexConsumer.retryInitialIntervalMillis}") long retryInitialIntervalMillis,
            @Value("${custom.product.search.indexConsumer.retryMaxIntervalMillis}") long retryMaxIntervalMillis) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);   // 기본 설정(record)은 묶음 리스너에 맞지 않는다

        ExponentialBackOff backOff = new ExponentialBackOff(retryInitialIntervalMillis, 2.0);
        backOff.setMaxInterval(retryMaxIntervalMillis);   // 최대 경과 시간은 기본값(제한 없음)
        factory.setCommonErrorHandler(new DefaultErrorHandler(backOff));
        return factory;
    }
}
