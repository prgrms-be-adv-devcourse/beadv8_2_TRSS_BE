package com.backend.boundedContext.delivery.in;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.backend.boundedContext.delivery.app.DeliveryFacade;
import com.backend.boundedContext.delivery.domain.DeliveryTrackerPolicy;
import com.backend.boundedContext.delivery.in.dto.TrackerCallbackRequest;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Delivery Tracker 웹훅 콜백
 */
@Slf4j
@Hidden
@RestController
@RequiredArgsConstructor
public class DeliveryTrackerWebhookController {

    private final DeliveryTrackerPolicy deliveryTrackerPolicy;
    private final DeliveryFacade deliveryFacade;

    @PostMapping("/webhooks/delivery-tracker/{token}")
    public ResponseEntity<Void> callback(@PathVariable String token,
                                         @RequestBody(required = false) TrackerCallbackRequest body) {
        if (!deliveryTrackerPolicy.matchesCallbackToken(token)) {
            log.warn("Delivery Tracker 콜백 토큰이 맞지 않습니다");
            return ResponseEntity.notFound().build();
        }
        if (body != null && body.carrierId() != null && body.trackingNumber() != null) {
            try {
                deliveryFacade.requestTrackingCheck(body.carrierId(), body.trackingNumber());
            } catch (Exception e) {
                // 경로·토큰을 남기지 않는다. 예외 메시지에는 토큰이 없다(Facade에 넘기지 않음)
                log.error("Delivery Tracker 콜백 처리에 실패했습니다. 503을 돌려줍니다(재시도 또는 안전망이 메움)", e);
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
        }
        return ResponseEntity.accepted().build();
    }
}
