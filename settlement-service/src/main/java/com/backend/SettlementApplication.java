package com.backend;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SettlementApplication {

	public static final String TIME_ZONE = "Asia/Seoul";

	public static void main(String[] args) {
		// 정책(매월 정산일, 재시도 시각, 정산월 = 확정일 KST 기준 월)이 모두 KST 기준이므로 JVM 기본 시간대를 KST로 고정한다.
		TimeZone.setDefault(TimeZone.getTimeZone(TIME_ZONE));
		SpringApplication.run(SettlementApplication.class, args);
	}
}
