package com.backend.boundedContext.delivery.out;

/**
 * Delivery Tracker 호출 실패.
 * 이 클래스 자체는 HTTP 200 응답 안의 건별 GraphQL 오류(NOT_FOUND·모르는 코드 등)다. 그 건만 실패로 세고 회차는 계속한다.
 * 서비스 전체 장애(접속 실패·시간 초과·5xx·해석할 수 없는 응답)는 하위 클래스 {@link DeliveryTrackerServiceDownException}이고 회차를 멈춘다.
 */
public class DeliveryTrackerUnavailableException extends RuntimeException {

    public DeliveryTrackerUnavailableException(String message) {
        super(message);
    }

    public DeliveryTrackerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
