# syntax=docker/dockerfile:1
# ShellMind server image (multi-stage; build context is server/)
#   docker build -t shellmind-server:latest server

# ---------- Build ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
# Copy poms first so dependency layers can be cached
COPY pom.xml .
COPY shellmind-server-api/pom.xml shellmind-server-api/
COPY shellmind-server-app/pom.xml shellmind-server-app/
COPY shellmind-server-case/pom.xml shellmind-server-case/
COPY shellmind-server-domain/pom.xml shellmind-server-domain/
COPY shellmind-server-infrastructure/pom.xml shellmind-server-infrastructure/
COPY shellmind-server-trigger/pom.xml shellmind-server-trigger/
COPY shellmind-server-types/pom.xml shellmind-server-types/
RUN mvn -B -q dependency:go-offline -DskipTests || true
COPY . .
RUN mvn -B -q -DskipTests package

# ---------- Runtime ----------
FROM eclipse-temurin:17-jre
ENV TZ=Asia/Shanghai \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"
RUN groupadd --system shellmind && useradd --system --gid shellmind --home /app shellmind \
    && mkdir -p /app/data/log && chown -R shellmind:shellmind /app
WORKDIR /app
COPY --from=build --chown=shellmind:shellmind /build/shellmind-server-app/target/shellmind-server.jar app.jar
USER shellmind
EXPOSE 8091
VOLUME ["/app/data"]
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
