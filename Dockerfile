# syntax=docker/dockerfile:1

FROM eclipse-temurin:21.0.12.1_1-jdk-noble AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY chat-domain chat-domain
COPY chat-persistence chat-persistence
COPY chat-api chat-api
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew \
 && ./gradlew --no-daemon :chat-api:bootJar \
 && java -Djarmode=tools -jar chat-api/build/libs/chat-api-*.jar extract --layers --launcher --destination /extracted

FROM eclipse-temurin:21.0.12.1_1-jre-noble
RUN groupadd --system app && useradd --system --gid app --home-dir /app app \
 && mkdir -p /app/logs && chown -R app:app /app
WORKDIR /app
COPY --from=build --chown=app:app /extracted/dependencies/ ./
COPY --from=build --chown=app:app /extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /extracted/application/ ./
USER app
EXPOSE 8080 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
