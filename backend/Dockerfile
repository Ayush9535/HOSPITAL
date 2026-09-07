# Build stage
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY . .
RUN mvn clean package -DskipTests

# Run stage
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/target/hospital-management-system-1.0.0.jar app.jar
# Run as a non-root user (Trivy DS002 / CIS): create an unprivileged account and drop to it so a
# container escape does not run as root. The JVM only needs to read app.jar and bind port 8080.
RUN groupadd --system --gid 1001 appuser \
    && useradd --system --uid 1001 --gid 1001 appuser \
    && chown -R appuser:appuser /app
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
