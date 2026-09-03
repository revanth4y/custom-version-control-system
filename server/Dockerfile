# Built from the repository root rather than from server/, because the server
# now depends on the engine module and a build context of server/ cannot see it.
#
# Dependencies are resolved in their own layer so that source-only changes do
# not re-download the world on every rebuild.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY engine/pom.xml engine/
COPY server/pom.xml server/
COPY cli/pom.xml cli/
RUN chmod +x mvnw && ./mvnw -B -q -pl engine,server -am dependency:go-offline

COPY engine/src/ engine/src/
COPY server/src/ server/src/
# -am builds the engine the server needs; the cli is not part of this image.
RUN ./mvnw -B -q -pl server -am clean package -DskipTests

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

COPY --from=build /build/server/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
