import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
	java
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

// 모든 모듈 공통 설정. 루트 src(기존 모놀리스)도 같은 설정이 필요해서 allprojects로 둔다.
// 모놀리스를 다 옮기면 subprojects로 바꾼다.
allprojects {
	apply(plugin = "java")
	apply(plugin = "io.spring.dependency-management")

	group = "com.backend"
	version = "0.0.1-SNAPSHOT"

	repositories {
		mavenCentral()
	}

	configure<JavaPluginExtension> {
		toolchain {
			languageVersion = JavaLanguageVersion.of(21)
		}
	}

	// Boot 플러그인이 없는 모듈(common 등)도 Boot BOM으로 버전을 맞춘다
	configure<DependencyManagementExtension> {
		imports {
			mavenBom(SpringBootPlugin.BOM_COORDINATES)
		}
	}

	dependencies {
		"compileOnly"("org.projectlombok:lombok")
		"annotationProcessor"("org.projectlombok:lombok")
		"testCompileOnly"("org.projectlombok:lombok")
		"testAnnotationProcessor"("org.projectlombok:lombok")
		"testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
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

	// Boot 플러그인을 적용한 모듈(루트, 이후 서비스 모듈)의 bootRun도 KST로 실행한다
	plugins.withId("org.springframework.boot") {
		tasks.named<JavaExec>("bootRun") {
			jvmArgs("-Duser.timezone=Asia/Seoul")
		}
	}
}

// 기존 모놀리스 (루트 src). 서비스로 옮겨진 컨텍스트만큼 의존성도 줄여 간다.
dependencies {
	// webmvc, data-jpa, validation, security, kafka, jjwt, springdoc은 common이 api로 준다
	implementation(project(":common"))
	implementation("org.springframework.boot:spring-boot-starter-batch-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-mail")
	// 상품 이미지 S3 저장소 (custom.product.image.storage=s3일 때만 사용)
	implementation(platform("software.amazon.awssdk:bom:2.52.0"))
	implementation("software.amazon.awssdk:s3")

	developmentOnly("org.springframework.boot:spring-boot-devtools")
	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")
	testImplementation(testFixtures(project(":common")))   // OutboxTestEvent
	testImplementation("org.springframework.boot:spring-boot-starter-batch-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
}
