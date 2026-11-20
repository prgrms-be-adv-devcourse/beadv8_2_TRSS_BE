package com.backend;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NotificationApplication {

	public static final String TIME_ZONE = "Asia/Seoul";

	public static void main(String[] args) {
		// 알림 시각(주문 완료·발송·배송 완료)을 다른 서비스와 같은 KST 기준으로 다루도록 JVM 기본 시간대를 KST로 고정한다.
		TimeZone.setDefault(TimeZone.getTimeZone(TIME_ZONE));
		SpringApplication.run(NotificationApplication.class, args);
	}
}
