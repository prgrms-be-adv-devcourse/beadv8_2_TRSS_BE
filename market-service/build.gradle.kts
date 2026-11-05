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

	developmentOnly("org.springframework.boot:spring-boot-devtools")
	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")
	testImplementation(testFixtures(project(":common")))   // OutboxTestEvent
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
}
