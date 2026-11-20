package com.backend.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.handler.RoutePredicateHandlerMapping;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 경로마다 어느 서비스 규칙(route id)이 골라지는지 확인한다. 실제 서비스로 넘기지는 않는다.
 * nginx(infra/nginx/default.conf)가 나누던 경로와 같아야 한다.
 */
@SpringBootTest
class GatewayRouteTest {

    @Autowired
    private RoutePredicateHandlerMapping routeHandlerMapping;

    @Autowired
    private ApplicationContext context;

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            // member
            "/api/v1/auth/login, member",
            "/api/v1/users/me, member",
            "/api/v1/sellers, member",
            "/api/v1/seller/me, member",
            "/api/v1/seller/influencers, member",
            "/api/v1/influencers/3, member",
            "/api/v1/admin/users/1, member",
            "/api/v1/admin/sellers, member",
            "/api/v1/admin/influencers, member",
            // users/ 아래지만 market (member보다 먼저 골라야 함)
            "/api/v1/users/me/coupon-usages, market-coupon-usages",
            // market
            "/api/v1/products, market",
            "/api/v1/products/10, market",
            "/api/v1/categories, market",
            "/api/v1/cart/items, market",
            "/api/v1/orders/5/return, market",
            "/api/v1/wishlist, market",
            "/api/v1/reviews, market",
            "/api/v1/coupons, market",
            "/api/v1/influencer/partnerships, market",
            "/api/v1/seller/products, market",
            "/api/v1/seller/order-items, market",
            "/api/v1/seller/partnerships, market",
            "/api/v1/seller/coupons, market",
            "/api/v1/admin/products, market",
            "/api/v1/admin/categories, market",
            "/api/v1/admin/product-stop-requests, market",
            "/api/v1/admin/coupons, market",
            "/api/v1/admin/partnerships, market",
            "/api/v1/admin/returns, market",
            "/images/products/a.png, market",
            // payment
            "/api/v1/wallet, payment",
            "/api/v1/topups/hecto/result, payment",
            "/api/v1/payments/refunds, payment",
            "/api/v1/admin/payments, payment",
            "/api/v1/admin/topups, payment",
            "/v3/api-docs, payment",
            // settlement
            "/api/v1/admin/settlements, settlement",
            "/api/v1/admin/settlement-candidates, settlement",
            "/api/v1/seller/settlements, settlement",
            // delivery
            "/api/v1/logistics/deliveries, delivery",
            "/api/v1/admin/deliveries, delivery",
            "/webhooks/delivery-tracker/secret-token, delivery",
            // notification
            "/api/v1/notifications/unread-count, notification",
            "/api/v1/notifications/subscribe, notification-subscribe",   // 알림 실시간 구독(SSE): nginx 버퍼링을 끄는 헤더를 붙인다
            // ai (관리자 FAQ는 admin/ 아래지만 ai)
            "/api/v1/ai/recommendations, ai",
            "/api/v1/ai/faqs, ai",
            "/api/v1/admin/faqs, ai",
            // 서비스별 Swagger 문서
            "/v3/api-docs/member, member",
            "/v3/api-docs/market, market",
            "/v3/api-docs/payment, payment",
            "/v3/api-docs/settlement, settlement",
            "/v3/api-docs/delivery, delivery",
            "/v3/api-docs/notification, notification",
            "/v3/api-docs/ai, ai",
    })
    void 경로에_맞는_서비스_규칙을_고른다(String path, String expectedRouteId) {
        assertThat(findRouteId(path)).isEqualTo(expectedRouteId);
    }

    // 이름이 비슷하지만 다른 경로가 잘못 걸리지 않는지 (예: /api/v1/products-x는 products가 아님)
    @ParameterizedTest
    @ValueSource(strings = {"/internal/v1/payments/1", "/internal/v1/orders", "/api/v1/products-x", "/api/v1/unknown"})
    void 규칙에_없는_경로는_어느_서비스로도_넘기지_않는다(String path) {
        assertThat(findRouteId(path)).isNull();
    }

    @Test
    void 내부_API는_밖에서_부르면_404다() {
        WebTestClient.bindToApplicationContext(context).build()
                .get().uri("/internal/v1/payments/1")
                .exchange()
                .expectStatus().isNotFound();
    }

    private String findRouteId(String path) {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path));
        routeHandlerMapping.getHandler(exchange).block();
        Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
        return route == null ? null : route.getId();
    }
}
