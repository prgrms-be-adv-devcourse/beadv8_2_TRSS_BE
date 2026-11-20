// market-service: 상품·검색·리뷰·찜·팔레트(상품 쪽)와 장바구니·주문(주문 쪽).
// Java 21·Lombok·Boot BOM·테스트 설정은 루트 allprojects에서 온다.
import java.time.Duration

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
	// 인기 검색어 (시간 단위 Redis ZSET)
	implementation("org.springframework.boot:spring-boot-starter-data-redis")

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
// 측정 테스트(@Tag("measure"))는 로컬 DB·ES로 응답 시간·쿼리 수를 재서 로그에 남기기만 한다. 기본 test에서 빼고 measureTest로 따로 돌린다
tasks.test {
	useJUnitPlatform {
		excludeTags("es", "measure")
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

// 실행: ./gradlew :market-service:measureTest 후 로그의 [측정] 줄을 본다. 공용 개발 서버가 아니라 로컬 DB·Redis로만 돌린다
tasks.register<Test>("measureTest") {
	description = "상품 상세·검색 읽기 측정 (쿼리 수, 응답 시간, 외부 호출이 느릴 때 DB 커넥션 점유). 단언하지 않고 기록만 한다"
	group = "verification"
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	useJUnitPlatform {
		includeTags("measure")
	}
	outputs.upToDateWhen { false }   // 같은 코드라도 다시 잴 수 있게 한다
	timeout.set(Duration.ofMinutes(15))   // 측정이 멈춰도 끝나게 한다
	testLogging {
		showStandardStreams = true
	}
}
