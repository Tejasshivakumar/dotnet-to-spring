# Portway: one image with the API, the pipeline and the web UI.
#
#   docker build -f docker/Dockerfile -t portway .
#
# Three stages, each cached on its own: the web build, the Maven build, and a
# slim runtime. Dependencies are resolved in a layer before any source is
# copied, so a code change does not re-download the world.

FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# The build selects its JDK through Maven toolchains; declare the image's JDK.
RUN mkdir -p /root/.m2 && printf '%s\n' \
  '<toolchains><toolchain><type>jdk</type>' \
  '<provides><version>21</version></provides>' \
  "<configuration><jdkHome>${JAVA_HOME}</jdkHome></configuration>" \
  '</toolchain></toolchains>' > /root/.m2/toolchains.xml
COPY pom.xml .
COPY portway-core/pom.xml portway-core/
COPY portway-app/pom.xml portway-app/
RUN mvn -B -q dependency:go-offline -DexcludeReactor=true || true
COPY portway-core portway-core
COPY portway-app portway-app
COPY sample-dotnet sample-dotnet
COPY --from=web /web/dist portway-app/src/main/resources/static
RUN mvn -B -q -DskipTests -Djacoco.skip=true package

# A JDK, not a JRE: compile verification and google-java-format both need the
# jdk.compiler module at runtime.
FROM eclipse-temurin:21-jdk-alpine
RUN addgroup -S portway && adduser -S portway -G portway \
  && mkdir -p /var/portway && chown portway:portway /var/portway
WORKDIR /app
COPY --from=build /src/portway-app/target/portway-app-*.jar app.jar
USER portway
ENV PORTWAY_WORK_DIR=/var/portway \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q UP || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
