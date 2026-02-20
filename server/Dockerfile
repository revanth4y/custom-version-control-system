# Build stage: dependencies are resolved in their own layer so that source-only
# changes do not re-download the world on every rebuild.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

# Runs unprivileged: nothing in the server needs root.
RUN useradd --system --create-home --shell /usr/sbin/nologin gitforge
USER gitforge

COPY --from=build /build/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
