FROM eclipse-temurin:25-jdk AS builder
WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY shared shared
COPY apps apps
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
RUN chmod +x mvnw && ./mvnw -pl apps/platform-server -am package -DskipTests -B

FROM eclipse-temurin:25-jre
LABEL org.opencontainers.image.licenses="MIT"
RUN apt-get update && apt-get install -y --no-install-recommends curl git ca-certificates && rm -rf /var/lib/apt/lists/*
RUN groupadd -r appuser && useradd -r -g appuser appuser
WORKDIR /app
COPY LICENSE /licenses/SpaceAgent-MIT.txt
COPY THIRD_PARTY_NOTICES.md /licenses/THIRD_PARTY_NOTICES.md
COPY ASSET-PROVENANCE.md /licenses/ASSET-PROVENANCE.md
COPY licenses /licenses
COPY --from=builder /build/apps/platform-server/target/*-exec.jar app.jar
RUN mkdir -p /app/diagnostics /data/workspaces /data/knowledge-index \
    && chown -R appuser:appuser /app/diagnostics /data/workspaces /data/knowledge-index \
    && chmod 700 /data/knowledge-index
USER appuser
EXPOSE 9000
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD curl -f http://localhost:9000/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
