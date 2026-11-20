package com.backend;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PaymentApplication {

	public static final String TIME_ZONE = "Asia/Seoul";

	public static void main(String[] args) {
		// 주문 결제 만료·충전 만료 시각이 모두 KST 기준이므로 JVM 기본 시간대를 KST로 고정한다.
		TimeZone.setDefault(TimeZone.getTimeZone(TIME_ZONE));
		SpringApplication.run(PaymentApplication.class, args);
	}
}
