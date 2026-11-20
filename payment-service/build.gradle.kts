// payment-service: 예치금 지갑·충전(토스)·주문 결제·정산 지급.
// Java 21·Lombok·Boot BOM·테스트 설정은 루트 allprojects에서 온다.
plugins {
	id("org.springframework.boot")
}

dependencies {
	// webmvc, data-jpa, validation, security, kafka, jjwt, springdoc은 common이 api로 준다
	implementation(project(":common"))
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	// 충전 PG별 서킷 브레이커. 설정(resilience4j.circuitbreaker.*)·Prometheus 메트릭은 스프링 연동 모듈이 만들고,
	// 호출을 막는 AOP(@CircuitBreaker) 대신 코드로 결과만 기록한다(PgCircuitBreakers)
	implementation("io.github.resilience4j:resilience4j-spring-boot4:2.4.0")
	implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.4.0")

	developmentOnly("org.springframework.boot:spring-boot-devtools")
	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")
	testImplementation(testFixtures(project(":common")))   // OutboxTestEvent
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
}
