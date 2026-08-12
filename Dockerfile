# Multi-stage build: frontend-maven-plugin downloads its own Node.js during `mvn package`,
# so the build stage only needs a JDK + Maven — no separate Node stage required.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
COPY frontend ./frontend
RUN mvn -B -ntp -DskipTests package

# PPT rendering shells out to a Python script at runtime (ProcessBuilder + python-pptx), so the
# runtime image needs Python alongside the JRE — and the script/templates copied out to real files
# on disk, since inside the jar they're classpath resources, not something ProcessBuilder can exec.
# See application-docker.yml for the matching agenttrail.ppt.* path overrides.
FROM eclipse-temurin:21-jre AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-pip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /build/src/main/resources/ppt-scripts ./ppt-scripts
COPY --from=build /build/src/main/resources/ppt-templates ./ppt-templates
RUN pip3 install --no-cache-dir --break-system-packages -r ppt-scripts/requirements.txt \
    && mkdir -p /app/ppt-output
COPY --from=build /build/target/agent-trail-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-Dspring.profiles.active=docker", "-jar", "/app/app.jar"]
