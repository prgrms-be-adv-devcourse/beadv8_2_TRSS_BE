package com.backend.boundedContext.product.in;

import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

/**
 * 회원 추천 다시 계산 컨슈머 전용 리스너 팩토리. 묶음으로 받아 같은 회원을 한 번만 계산하려고 묶음 리스너로 둔다
 * (공통 설정은 메시지 단위 커밋이라 묶음 리스너에 쓸 수 없다). 리스너가 예외를 던지지 않으므로 재시도 설정은 두지 않는다.
 */
@Configuration
public class ProductRecommendKafkaConfig {

    static final String LISTENER_FACTORY = "productRecommendListenerFactory";

    @Bean(name = LISTENER_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<Object, Object> productRecommendListenerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        return factory;
    }
}
