// ai-service: 뷰티 설문, 피부 맞춤 상품 추천(피하는 성분 제외·임베딩), 피부가 비슷한 크리에이터 추천, FAQ 근거 챗봇.
// Java 21·Lombok·Boot BOM·테스트 설정은 루트 allprojects에서 온다.
plugins {
	id("org.springframework.boot")
}

// Spring AI 2.0.x는 Spring Boot 4.1 기준이라 우리 Boot 4.1.x와 맞는다. 2.1.x는 Boot 4.2용이라 올리지 않는다
dependencyManagement {
	imports {
		mavenBom("org.springframework.ai:spring-ai-bom:2.0.2")
	}
}

dependencies {
	// webmvc, data-jpa, validation, security, kafka, jjwt, springdoc은 common이 api로 준다
	implementation(project(":common"))
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	// 임베딩(EmbeddingModel)과 챗봇 답변(ChatModel)을 쓴다. 벡터 저장·검색은 JdbcTemplate SQL로 한다(피하는 성분·노출 여부 필터를 같은 SQL에 두려고)
	implementation("org.springframework.ai:spring-ai-starter-model-openai")
	// 챗봇 회원별 요청 횟수 제한
	implementation("org.springframework.boot:spring-boot-starter-data-redis")

	developmentOnly("org.springframework.boot:spring-boot-devtools")
	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")
	testImplementation(testFixtures(project(":common")))
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
}
