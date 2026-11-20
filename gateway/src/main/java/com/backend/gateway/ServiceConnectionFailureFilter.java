package com.backend.gateway;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import java.io.IOException;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * 서비스에서 응답을 받지 못한 요청을 요청 주소 없이 WARN으로 기록한다.
 * <ul>
 *   <li>연결 실패(연결 거절·컨테이너 없음·연결 끊김) → 502</li>
 *   <li>응답 시간 초과(application.yaml의 response-timeout, 60초) → 504</li>
 * </ul>
 * 그냥 두면 연결 실패는 Spring 기본 오류 처리기가 500과 함께 요청 주소 전체를 ERROR로 찍고, 시간 초과는 아무 기록도 남지 않는다.
 * 이 WARN은 Grafana 알림 규칙 "gateway 서비스 응답 실패"가 세고(배포 중에는 scripts/deploy.sh가 무음으로 둔다),
 * ERROR가 아니라서 "앱 ERROR 로그" 알림으로는 가지 않는다(배포 재시작마다 울리지 않게).
 * 배송 웹훅(/webhooks/delivery-tracker/{토큰})은 주소 끝이 비밀 토큰이라 어떤 오류든 주소 없이 처리한다
 * (nginx도 이 경로의 접근 로그를 끈다: infra/nginx/default.conf).
 * 그 밖의 오류(코드 버그 등)는 그대로 두어 지금처럼 ERROR로 남게 한다.
 */
@Slf4j
@Component
public class ServiceConnectionFailureFilter implements GlobalFilter, Ordered {

    private static final String WEBHOOK_PATH_PREFIX = "/webhooks/delivery-tracker/";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        boolean webhook = exchange.getRequest().getPath().value().startsWith(WEBHOOK_PATH_PREFIX);
        return chain.filter(exchange).onErrorResume(e -> webhook || e instanceof IOException || isTimeout(e), e -> {
            Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
            String routeId = route == null ? "-" : route.getId();
            // 응답을 보내던 중 끊김(알림 구독 연결이 끊기거나 큰 응답을 받다 끊김). 서비스는 응답을 했으므로 '응답 실패' 알림 대상이 아니다.
            // 이미 보낸 응답이라 상태 코드도 바꿀 수 없으니 그냥 끝낸다
            if (exchange.getResponse().isCommitted()) {
                log.debug("응답을 보내던 중 연결이 끊겼습니다 (route={}, {})", routeId, e.getClass().getSimpleName());
                return Mono.empty();
            }
            // 예외 메시지는 연결 대상(서비스 주소)·대기 시간만 담고 요청 주소는 담지 않는다. 스택은 남기지 않는다(Reactor가 요청 주소를 붙인다)
            // WARN 두 가지만 알림 규칙 "gateway 서비스 응답 실패"가 센다(rules.yaml)
            HttpStatus status;
            if (isTimeout(e)) {
                log.warn("서비스 응답 시간 초과 (route={}, {})", routeId, e.getMessage());
                status = HttpStatus.GATEWAY_TIMEOUT;
            } else if (e instanceof IOException) {
                log.warn("서비스에 연결하지 못했습니다 (route={}, {}: {})", routeId, e.getClass().getSimpleName(), e.getMessage());
                status = HttpStatus.BAD_GATEWAY;
            } else {
                // 배송 웹훅의 연결 문제가 아닌 오류(코드 버그 등). 진짜 오류라 ERROR로 남겨 "앱 ERROR 로그" 알림으로 보낸다
                log.error("배송 웹훅 처리 중 오류 (route={}, {}: {})", routeId, e.getClass().getSimpleName(), e.getMessage());
                status = HttpStatus.BAD_GATEWAY;
            }
            exchange.getResponse().setStatusCode(status);   // nginx만 있을 때와 같은 응답
            return exchange.getResponse().setComplete();
        });
    }

    // 응답 시간을 넘기면 gateway(NettyRoutingFilter)가 504 ResponseStatusException으로 바꿔 던진다
    private static boolean isTimeout(Throwable e) {
        return e instanceof ResponseStatusException rse && rse.getStatusCode().value() == HttpStatus.GATEWAY_TIMEOUT.value();
    }

    // 다른 필터·서비스로 넘기기(NettyRoutingFilter)에서 난 오류까지 받도록 가장 먼저 돈다
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
