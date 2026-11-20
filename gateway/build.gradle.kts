// API Gateway (Spring Cloud Gateway, WebFlux). 서비스별 경로 나누기와 JWT 검사를 맡는다.
// WebFlux라서 webmvc를 api로 주는 common에는 의존하지 않는다.
// Java 21·Lombok·Boot BOM·테스트 설정은 루트 allprojects에서 온다.
plugins {
	id("org.springframework.boot")
}

dependencies {
	implementation(platform("org.springframework.cloud:spring-cloud-dependencies:2025.1.4"))
	implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
	// Access Token 검사. common과 같은 버전
	implementation("io.jsonwebtoken:jjwt-api:0.12.6")
	runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
	runtimeOnly("io.jsonwebtoken:jjwt-gson:0.12.6")
	// 상태 확인(/actuator/health)과 지표(/actuator/prometheus). 서버에선 관리 포트 9091에 뜨고, Alloy가 다른 앱처럼 수집한다
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("io.micrometer:micrometer-registry-prometheus")
	// 분산 추적: 사용자 요청마다 traceId를 처음 만들어 서비스로 넘기고, 구간 기록을 Tempo로 보낸다(common과 같은 스타터)
	implementation("org.springframework.boot:spring-boot-starter-opentelemetry")

	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")   // WebTestClient
}
