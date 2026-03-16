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

# Repository storage is the one piece of state the server cannot rebuild, so it
# lives outside the image on a mounted volume. Created here with the right
# ownership because Docker seeds a new named volume from this path, permissions
# included, and the process does not run as root.
RUN mkdir -p /var/lib/gitforge/storage && chown -R gitforge:gitforge /var/lib/gitforge
VOLUME ["/var/lib/gitforge/storage"]

USER gitforge

COPY --from=build /build/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
