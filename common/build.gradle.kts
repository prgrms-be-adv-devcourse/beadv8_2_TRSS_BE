// 모든 서비스가 쓰는 global·shared·standard. 실행 jar가 아니라 라이브러리 jar로 만든다.
// Spring Boot 플러그인을 적용하지 않아서 bootJar·bootRun 태스크가 없다.
// Java 21·Lombok·Boot BOM·테스트 설정은 루트 allprojects에서 온다.
plugins {
	`java-library`
	`java-test-fixtures`   // OutboxTestEvent를 루트·서비스 테스트와 같이 쓴다
}

dependencies {
	// common의 public 타입이 그대로 드러나는 의존성은 api로 노출한다
	api("org.springframework.boot:spring-boot-starter-webmvc")
	api("org.springframework.boot:spring-boot-starter-data-jpa")
	api("org.springframework.boot:spring-boot-starter-validation")
	api("org.springframework.boot:spring-boot-starter-security")
	api("org.springframework.boot:spring-boot-starter-kafka")
	api("io.jsonwebtoken:jjwt-api:0.12.6")
	runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
	runtimeOnly("io.jsonwebtoken:jjwt-gson:0.12.6")
	api("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")
	// 메트릭: /actuator/prometheus (Alloy가 스크랩 → Prometheus). 기본값은 observability-defaults.properties
	api("org.springframework.boot:spring-boot-starter-actuator")
	api("io.micrometer:micrometer-registry-prometheus")
	// 스프링이 만들어 주는 RestClient.Builder(Boot 4에서 따로 나뉜 모듈). 이 builder로 만든 클라이언트는
	// 호출 기록(http_client_requests)이 남고, 분산 추적의 traceId가 다음 서비스로 넘어간다
	api("org.springframework.boot:spring-boot-starter-restclient")
	// 분산 추적: 요청마다 traceId를 붙여 다음 서비스로 넘기고, 구간 기록을 Tempo로 보낸다. 설정은 observability-defaults.properties
	api("org.springframework.boot:spring-boot-starter-opentelemetry")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")     // ActuatorAccessTest
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
}
