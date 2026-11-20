# syntax=docker/dockerfile:1
# 운영 배포용 앱 이미지. JDK 이미지에서 jar를 빌드하고, 실행 이미지에는 JRE와 jar만 담는다.
#
# gateway와 서비스 7개(compose.prod.yaml의 gateway·member·market·payment·settlement·delivery·notification·ai)가 이 파일 하나로 이미지를 만든다.
# 1단계(build)는 MODULE과 상관없이 모든 서비스에서 똑같아서, compose가 여러 개를 같이 빌드하면
# Docker(BuildKit)가 같은 작업으로 보고 한 번만 실행한다. 2단계에서 MODULE로 자기 jar만 가져간다.

# 1단계: 빌드 (gateway·서비스 공통, 한 번만 실행)
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
COPY notification-service/build.gradle.kts notification-service/
COPY ai-service/build.gradle.kts ai-service/

# 서비스가 분리될 때마다 그 모듈의 src를 여기와 아래 SERVICES에 추가한다.
COPY common/src common/src
COPY gateway/src gateway/src
COPY member-service/src member-service/src
COPY market-service/src market-service/src
COPY payment-service/src payment-service/src
COPY settlement-service/src settlement-service/src
COPY delivery-service/src delivery-service/src
COPY notification-service/src notification-service/src
COPY ai-service/src ai-service/src

# Gradle을 한 번만 띄워 실행 jar 7개를 같이 만든다. common은 한 번만 컴파일해 7개가 같이 쓴다.
#   --parallel   : 서로 기다릴 필요 없는 서비스 컴파일을 Gradle 하나 안에서 동시에 돌린다
#   --build-cache: 이전 배포와 코드가 같은 모듈은 컴파일 결과를 캐시에서 꺼내 쓴다
#   -x test      : 테스트는 CI에서 돌리므로 생략한다
# --mount=type=cache: Gradle 배포판·의존성·빌드 캐시(/root/.gradle)를 서버에 남겨 다음 배포에서 다시 쓴다.
#   이미지에는 들어가지 않는다. 빌드 설정이 바뀌어도 받아 둔 의존성은 그대로 쓴다.
#   지우려면 서버에서 docker builder prune
# SERVICES: 만들 jar 목록. 기본은 전부. deploy.sh가 바뀐 앱만 빌드할 때 --build-arg로 그 앱만 넘긴다(예: "market-service").
#   한 번의 배포에서 빌드하는 이미지는 모두 같은 값을 받으므로, 이 단계는 여전히 한 번만 돈다.
ARG SERVICES="gateway member-service market-service payment-service settlement-service delivery-service notification-service ai-service"
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew \
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

# 배포 커밋(compose.prod.yaml의 build.args, deploy.sh가 넘김). 로그의 service.version에 들어가 Slack 에러 알림이 커밋 링크로 쓴다.
# compose의 environment가 아니라 이미지에 넣는다. environment에 두면 배포마다 값이 바뀌어
# 코드가 안 바뀐 앱까지 전부 다시 만들어지기 때문이다. 이미지에 두면 다시 빌드한 앱만 바뀐다.
ARG APP_VERSION=unknown
ENV LOGGING_STRUCTURED_ECS_SERVICE_VERSION=${APP_VERSION}

# actuator(/actuator/health·prometheus)는 앱 포트가 아닌 관리 포트 9091에 따로 띄운다.
# Alloy는 9091을 연 컨테이너만 스크랩하고(monitoring/alloy/config.alloy), nginx는 808x만 넘기므로 /actuator가 밖으로 나가지 않는다.
# 컨테이너마다 네트워크가 따로라 7개 앱이 같은 9091을 써도 충돌하지 않는다. 로컬 bootRun에는 적용되지 않는다(앱 포트에 뜬다)
ENV MANAGEMENT_SERVER_PORT=9091

# 추적(요청이 거친 서비스·구간 기록)을 보낼 곳. 같은 Compose의 Tempo(compose.prod.yaml)다(TRSS-335).
# 이 주소가 있을 때만 추적을 내보낸다. 로컬 bootRun·테스트에는 없어서 아무 데도 보내지 않는다. k3s ai는 deployment에서 내부 IP로 덮어쓴다
ENV MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://tempo:4318/v1/traces

# gateway 8000, member-service 8081, market-service 8082, payment-service 8083, settlement-service 8084, delivery-service 8085, notification-service 8086, ai-service 8087
# (각 application.yaml의 server.port). 9091은 관리 포트(위)
EXPOSE 8000 8081 8082 8083 8084 8085 8086 8087 9091
ENTRYPOINT ["java", "-jar", "app.jar"]
