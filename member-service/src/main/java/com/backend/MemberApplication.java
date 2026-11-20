package com.backend;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MemberApplication {

	public static final String TIME_ZONE = "Asia/Seoul";

	public static void main(String[] args) {
		// 다른 서비스와 같이 저장 시각(LocalDateTime)을 KST로 맞추려고 JVM 기본 시간대를 KST로 고정한다.
		TimeZone.setDefault(TimeZone.getTimeZone(TIME_ZONE));
		SpringApplication.run(MemberApplication.class, args);
	}
}
