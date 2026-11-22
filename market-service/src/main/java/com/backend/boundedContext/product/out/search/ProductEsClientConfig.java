package com.backend.boundedContext.product.out.search;

import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.elasticsearch.autoconfigure.ElasticsearchProperties;
import org.springframework.boot.elasticsearch.autoconfigure.Rest5ClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ES 요청이 응답을 기다리는 시간을 {@code spring.elasticsearch.socket-timeout}(3초)으로 제한한다.
 *
 * <p>Boot는 socket-timeout을 연결 설정에만 넣는다. 그런데 요청을 보내는 동안에는 요청 설정의 응답 제한이 그 값을 덮어쓰고,
 * ES 클라이언트는 응답 제한을 0(제한 없음)으로 둔다. 그래서 ES가 접속은 받고 응답하지 않으면(과부하·GC 멈춤) 요청이 끝나지 않고,
 * 검색은 DB 검색으로 대신하지 못한 채 요청 스레드와 DB 커넥션을 계속 잡는다. 같은 값을 응답 제한에도 넣어 시간이 지나면 실패하게 한다.
 */
@Configuration
public class ProductEsClientConfig {

    @Bean
    Rest5ClientBuilderCustomizer productEsResponseTimeout(ElasticsearchProperties properties) {
        return new Rest5ClientBuilderCustomizer() {
            @Override
            public void customize(Rest5ClientBuilder builder) {
            }

            @Override
            public void customize(RequestConfig.Builder requestConfigBuilder) {
                if (properties.getSocketTimeout() != null) {
                    requestConfigBuilder.setResponseTimeout(Timeout.of(properties.getSocketTimeout()));
                }
            }
        };
    }
}
