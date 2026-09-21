FROM eclipse-temurin:25-jdk AS builder
WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY shared shared
COPY apps/platform-server/pom.xml apps/platform-server/pom.xml
COPY apps/platform-admin-server apps/platform-admin-server
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
RUN chmod +x mvnw && ./mvnw -pl apps/platform-admin-server -am package -DskipTests -B

FROM eclipse-temurin:25-jre
LABEL org.opencontainers.image.licenses="MIT"
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
RUN groupadd -r adminapp && useradd -r -g adminapp adminapp
WORKDIR /app
COPY LICENSE /licenses/SpaceAgent-MIT.txt
COPY THIRD_PARTY_NOTICES.md /licenses/THIRD_PARTY_NOTICES.md
COPY ASSET-PROVENANCE.md /licenses/ASSET-PROVENANCE.md
COPY licenses /licenses
COPY --from=builder /build/apps/platform-admin-server/target/*-exec.jar app.jar
USER adminapp
EXPOSE 9400
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD curl -f http://localhost:9400/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
