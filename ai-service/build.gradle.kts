// ai-service: 뷰티 설문, 피부 맞춤 상품 추천(피하는 성분 제외·임베딩), 피부가 비슷한 크리에이터 추천.
// Java 21·Lombok·Boot BOM·테스트 설정은 루트 allprojects에서 온다.
// Spring AI(임베딩)는 상품 임베딩 단계에서 추가한다. 뼈대는 OpenAI 키 없이 뜬다.
plugins {
	id("org.springframework.boot")
}

dependencies {
	// webmvc, data-jpa, validation, security, kafka, jjwt, springdoc은 common이 api로 준다
	implementation(project(":common"))
	implementation("org.springframework.boot:spring-boot-starter-flyway")

	developmentOnly("org.springframework.boot:spring-boot-devtools")
	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")
	testImplementation(testFixtures(project(":common")))
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
}
