package com.backend.global.outbox;

/**
 * Kafka 브로커에 닿지 못해 발행하지 못했다(브로커 다운, 네트워크, 시간 초과).
 * 이벤트 자체의 문제가 아니므로 릴레이는 재시도 횟수를 올리지 않고 이번 실행을 멈춘 뒤 다음 실행에서 다시 보낸다.
 */
public class KafkaUnavailableException extends RuntimeException {

    public KafkaUnavailableException(Throwable cause) {
        super("Kafka 브로커에 발행하지 못했습니다: " + cause.getMessage(), cause);
    }
}
