# 운영 배포용 앱 이미지. JDK 이미지에서 jar를 빌드하고, 실행 이미지에는 JRE와 jar만 담는다.

# 1단계: 빌드
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# 빌드 설정만 먼저 복사해 의존성을 받아 둔다. 소스만 바뀌면 이 레이어는 캐시를 재사용한다.
COPY gradlew settings.gradle.kts build.gradle.kts lombok.config ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon > /dev/null

# 테스트는 CI에서 돌리므로 이미지 빌드에서는 생략한다.
COPY src src
RUN ./gradlew bootJar -x test --no-daemon && cp build/libs/*.jar app.jar

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

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
