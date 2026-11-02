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

	testImplementation("org.springframework.boot:spring-boot-starter-test")
}
