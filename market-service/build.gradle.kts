// market-service: 상품·검색·리뷰·찜·팔레트(상품 쪽)와 장바구니·주문(주문 쪽).
// Java 21·Lombok·Boot BOM·테스트 설정은 루트 allprojects에서 온다.
plugins {
	id("org.springframework.boot")
}

dependencies {
	// webmvc, data-jpa, validation, security, kafka, jjwt, springdoc은 common이 api로 준다
	implementation(project(":common"))
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	// 상품 이미지 S3 저장소 (custom.product.image.storage=s3일 때만 사용)
	implementation(platform("software.amazon.awssdk:bom:2.52.0"))
	implementation("software.amazon.awssdk:s3")
	// 상품 검색 Elasticsearch (custom.product.search.engine=es일 때만 사용). 버전은 Boot BOM이 서버(9.4.5)와 같은 클라이언트로 고른다
	implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")

	developmentOnly("org.springframework.boot:spring-boot-devtools")
	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")
	testImplementation(testFixtures(project(":common")))   // OutboxTestEvent
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
}

// ES 통합 테스트(@Tag("es"))는 로컬 compose의 ES가 필요하므로 기본 test에서 빼고 esTest로 따로 돌린다
// 실행: docker compose -f infra/compose.yaml up -d elasticsearch 후 ./gradlew :market-service:esTest
tasks.test {
	useJUnitPlatform {
		excludeTags("es")
	}
}

tasks.register<Test>("esTest") {
	description = "로컬 Elasticsearch가 필요한 상품 검색 통합 테스트"
	group = "verification"
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	useJUnitPlatform {
		includeTags("es")
	}
}
