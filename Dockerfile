# syntax=docker/dockerfile:1
# 운영 배포용 앱 이미지. JDK 이미지에서 jar를 빌드하고, 실행 이미지에는 JRE와 jar만 담는다.
#
# 서비스 4개(compose.prod.yaml의 member·market·payment·settlement)가 이 파일 하나로 이미지를 만든다.
# 1단계(build)는 MODULE과 상관없이 모든 서비스에서 똑같아서, compose가 4개를 같이 빌드하면
# Docker(BuildKit)가 같은 작업으로 보고 한 번만 실행한다. 2단계에서 MODULE로 자기 jar만 가져간다.

# 1단계: 빌드 (서비스 4개 공통, 한 번만 실행)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

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

# 서비스가 분리될 때마다 그 모듈의 src를 여기와 아래 SERVICES에 추가한다.
COPY common/src common/src
COPY member-service/src member-service/src
COPY market-service/src market-service/src
COPY payment-service/src payment-service/src
COPY settlement-service/src settlement-service/src

# Gradle을 한 번만 띄워 실행 jar 4개를 같이 만든다. common은 한 번만 컴파일해 4개가 같이 쓴다.
#   --parallel   : 서로 기다릴 필요 없는 서비스 컴파일을 Gradle 하나 안에서 동시에 돌린다
#   --build-cache: 이전 배포와 코드가 같은 모듈은 컴파일 결과를 캐시에서 꺼내 쓴다
#   -x test      : 테스트는 CI에서 돌리므로 생략한다
# --mount=type=cache: Gradle 배포판·의존성·빌드 캐시(/root/.gradle)를 서버에 남겨 다음 배포에서 다시 쓴다.
#   이미지에는 들어가지 않는다. 빌드 설정이 바뀌어도 받아 둔 의존성은 그대로 쓴다.
#   지우려면 서버에서 docker builder prune
RUN --mount=type=cache,target=/root/.gradle \
    SERVICES="member-service market-service payment-service settlement-service" \
    && chmod +x gradlew \
    && ./gradlew --no-daemon --parallel --build-cache -x test \
        $(for s in $SERVICES; do printf ':%s:bootJar ' "$s"; done) \
    && mkdir -p /jars \
    && for s in $SERVICES; do cp "$s"/build/libs/*.jar "/jars/$s.jar"; done

# 2단계: 실행 (서비스마다)
FROM eclipse-temurin:21-jre
WORKDIR /app

# 로그·cron 시각을 KST로 맞춘다. JVM 시간대는 Application.main에서도 KST로 고정한다.
ENV TZ=Asia/Seoul
# 컨테이너 메모리 제한(compose의 mem_limit) 안에서 힙을 잡는다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Seoul"

# root가 아닌 사용자로 실행한다.
RUN groupadd --system app && useradd --system --gid app app

# 어떤 서비스 이미지인지 (compose.prod.yaml의 build.args, 예: market-service). 비어 있으면 COPY가 실패한다.
ARG MODULE
COPY --from=build --chown=app:app /jars/${MODULE}.jar app.jar
USER app

# member-service 8081, market-service 8082, payment-service 8083, settlement-service 8084
# (각 application.yaml의 server.port)
EXPOSE 8081 8082 8083 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
