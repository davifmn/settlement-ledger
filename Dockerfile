# Build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q package -DskipTests

# Run: JRE only, non-root, read-only root filesystem friendly (writes only to /tmp)
FROM eclipse-temurin:21-jre-noble
RUN groupadd --system --gid 10001 ledger && useradd --system --uid 10001 --gid ledger ledger
WORKDIR /app
COPY --from=build /src/target/settlement-ledger.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
