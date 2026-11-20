// member-service: 회원·인증(이메일 인증, JWT, 카카오 로그인)·판매자.
// Java 21·Lombok·Boot BOM·테스트 설정은 루트 allprojects에서 온다.
plugins {
	id("org.springframework.boot")
}

dependencies {
	// webmvc, data-jpa, validation, security, kafka, jjwt, springdoc은 common이 api로 준다
	implementation(project(":common"))
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	// 이메일 인증코드·로그인 실패 횟수·Refresh Token 저장
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	// 이메일 인증코드 발송
	implementation("org.springframework.boot:spring-boot-starter-mail")

	developmentOnly("org.springframework.boot:spring-boot-devtools")
	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")
	testImplementation(testFixtures(project(":common")))   // OutboxTestEvent
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
}
