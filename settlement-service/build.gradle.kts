// settlement-service: 정산 후보, 월 정산 배치(Spring Batch), 지급.
// Java 21·Lombok·Boot BOM·테스트 설정은 루트 allprojects에서 온다.
plugins {
	id("org.springframework.boot")
}

dependencies {
	// webmvc, data-jpa, validation, security, kafka, jjwt, springdoc은 common이 api로 준다
	implementation(project(":common"))
	// 월 정산 Job. 배치 메타 테이블은 Flyway(V1__settlement_baseline.sql)로 만든다
	implementation("org.springframework.boot:spring-boot-starter-batch-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-flyway")

	developmentOnly("org.springframework.boot:spring-boot-devtools")
	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")
	testImplementation(testFixtures(project(":common")))   // OutboxTestEvent
	testImplementation("org.springframework.boot:spring-boot-starter-batch-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
	// 원장 무결성 1단계 규칙(정산 리포지토리 삭제 금지, JdbcTemplate 금지)을 테스트로 강제
	testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
}
