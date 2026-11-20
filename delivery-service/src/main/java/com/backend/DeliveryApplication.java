package com.backend;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DeliveryApplication {

	public static final String TIME_ZONE = "Asia/Seoul";

	public static void main(String[] args) {
		// 다른 서비스와 같이 정책 시간을 KST 기준으로 다루므로 JVM 기본 시간대를 KST로 고정한다.
		TimeZone.setDefault(TimeZone.getTimeZone(TIME_ZONE));
		SpringApplication.run(DeliveryApplication.class, args);
	}
}
