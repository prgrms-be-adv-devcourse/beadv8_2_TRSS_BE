# 운영 배포용 앱 이미지. JDK 이미지에서 jar를 빌드하고, 실행 이미지에는 JRE와 jar만 담는다.

# 1단계: 빌드
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 빌드 설정만 먼저 복사해 의존성을 받아 둔다. 소스만 바뀌면 이 레이어는 캐시를 재사용한다.
# settings에 등록한 모듈 폴더가 없으면 Gradle이 실패하므로 모듈마다 build.gradle.kts를 복사한다.
COPY gradlew settings.gradle.kts build.gradle.kts lombok.config ./
COPY gradle gradle
COPY common/build.gradle.kts common/
COPY gateway/build.gradle.kts gateway/
COPY member-service/build.gradle.kts member-service/
COPY market-service/build.gradle.kts market-service/
COPY payment-service/build.gradle.kts payment-service/
COPY settlement-service/build.gradle.kts settlement-service/
COPY delivery-service/build.gradle.kts delivery-service/
COPY ai-service/build.gradle.kts ai-service/
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon > /dev/null

# 테스트는 CI에서 돌리므로 이미지 빌드에서는 생략한다.
# MODULE로 고른 앱 하나의 실행 jar만 만든다(common jar는 그 안의 BOOT-INF/lib에 들어간다).
#   비워 두면 루트 모놀리스, market-service처럼 적으면 그 서비스 (compose.prod.yaml의 build.args)
# 서비스가 분리될 때마다 그 모듈의 src를 여기에 추가한다.
ARG MODULE=""
COPY common/src common/src
COPY src src
COPY member-service/src member-service/src
COPY market-service/src market-service/src
COPY payment-service/src payment-service/src
COPY settlement-service/src settlement-service/src
RUN if [ -z "$MODULE" ]; then \
        ./gradlew :bootJar -x test --no-daemon && cp build/libs/*.jar app.jar; \
    else \
        ./gradlew ":$MODULE:bootJar" -x test --no-daemon && cp "$MODULE"/build/libs/*.jar app.jar; \
    fi

# 2단계: 실행
FROM eclipse-temurin:21-jre
WORKDIR /app

# 로그·cron 시각을 KST로 맞춘다. JVM 시간대는 Application.main에서도 KST로 고정한다.
ENV TZ=Asia/Seoul
# 컨테이너 메모리 제한(compose의 mem_limit) 안에서 힙을 잡는다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Seoul"

# root가 아닌 사용자로 실행한다.
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build --chown=app:app /workspace/app.jar app.jar
USER app

# 루트 모놀리스 8080, member-service 8081, market-service 8082, payment-service 8083, settlement-service 8084
# (각 application.yaml의 server.port)
EXPOSE 8080 8081 8082 8083 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
