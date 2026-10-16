plugins {
	java
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.backend"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-batch-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-mail")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("io.jsonwebtoken:jjwt-api:0.12.6")
	runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
	runtimeOnly("io.jsonwebtoken:jjwt-gson:0.12.6")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")

	compileOnly("org.projectlombok:lombok")
	developmentOnly("org.springframework.boot:spring-boot-devtools")
	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")
	annotationProcessor("org.projectlombok:lombok")
	testImplementation("org.springframework.boot:spring-boot-starter-batch-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testCompileOnly("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testAnnotationProcessor("org.projectlombok:lombok")
}

// 정책 시간이 모두 KST 기준이므로 테스트·bootRun JVM도 KST로 실행한다.
tasks.withType<Test> {
	useJUnitPlatform()
	jvmArgs("-Duser.timezone=Asia/Seoul")
	// 테스트가 저장하는 상품 이미지는 build 폴더에 둔다 (로컬 개발용 ./uploads와 섞이지 않게)
	systemProperty("PRODUCT_IMAGE_DIR", layout.buildDirectory.dir("test-uploads").get().asFile.absolutePath)
	// 캐시된 테스트 컨텍스트마다 Hikari 풀(10)을 잡고 있어 기본값(32개)이면 Postgres max_connections(100)를 넘는다
	systemProperty("spring.test.context.cache.maxSize", "8")
}

tasks.named<JavaExec>("bootRun") {
	jvmArgs("-Duser.timezone=Asia/Seoul")
}
