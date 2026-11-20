package com.backend.boundedContext.delivery;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.backend.boundedContext.delivery.out.DeliveryTracker;
import com.backend.boundedContext.delivery.out.TrackResult;

/**
 * 테스트용 가짜 Delivery Tracker. 실제 API를 부르지 않는다.
 * 송장번호별로 track 결과(또는 예외)와 웹훅 등록 결과를 정해 두고, 받은 호출을 기록한다.
 * 정하지 않은 송장: track은 IN_TRANSIT, 웹훅 등록은 true.
 */
public class FakeDeliveryTracker implements DeliveryTracker {

    public record WebhookCall(String carrierId, String trackingNumber, String callbackUrl, OffsetDateTime expirationTime) {
    }

    private final Map<String, Supplier<TrackResult>> trackResults = new ConcurrentHashMap<>();
    private final Map<String, Supplier<Boolean>> webhookResults = new ConcurrentHashMap<>();
    private final List<String> trackCalls = new CopyOnWriteArrayList<>();
    private final List<WebhookCall> webhookCalls = new CopyOnWriteArrayList<>();

    /** track 결과. 예외를 던지거나 조회 중 일어나는 일(콜백·송장 수정)을 흉내 내려면 Supplier 안에서 한다 */
    public void onTrack(String trackingNumber, Supplier<TrackResult> result) {
        trackResults.put(trackingNumber, result);
    }

    public void onRegisterWebhook(String trackingNumber, Supplier<Boolean> result) {
        webhookResults.put(trackingNumber, result);
    }

    public List<String> trackCalls() {
        return trackCalls;
    }

    public List<WebhookCall> webhookCalls() {
        return webhookCalls;
    }

    public void reset() {
        trackResults.clear();
        webhookResults.clear();
        trackCalls.clear();
        webhookCalls.clear();
    }

    @Override
    public TrackResult track(String carrierId, String trackingNumber) {
        trackCalls.add(trackingNumber);
        return trackResults.getOrDefault(trackingNumber, () -> TrackResult.of("IN_TRANSIT", OffsetDateTime.now())).get();
    }

    @Override
    public boolean registerWebhook(String carrierId, String trackingNumber, String callbackUrl, OffsetDateTime expirationTime) {
        webhookCalls.add(new WebhookCall(carrierId, trackingNumber, callbackUrl, expirationTime));
        return webhookResults.getOrDefault(trackingNumber, () -> true).get();
    }

    /** 실제 DeliveryTrackerClient 대신 이 가짜를 주입한다. 테스트 클래스에서 @Import로 쓴다 */
    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        public FakeDeliveryTracker fakeDeliveryTracker() {
            return new FakeDeliveryTracker();
        }
    }
}
