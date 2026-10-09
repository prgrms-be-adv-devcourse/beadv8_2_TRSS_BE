package com.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class Application {

	public static final String TIME_ZONE = "Asia/Seoul";

	public static void main(String[] args) {
		// 정책(15분 만료, 7일 자동 확정, 매월 10일 정산)이 모두 KST 기준이므로 JVM 기본 시간대를 KST로 고정한다.
		TimeZone.setDefault(TimeZone.getTimeZone(TIME_ZONE));
		SpringApplication.run(Application.class, args);
	}

}
