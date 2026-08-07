# Stage 1: build
# Separate from runtime so the JDK and Maven are not in the final image.
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy pom first — Docker caches this layer until pom.xml changes,
# so dependency downloads are skipped on source-only rebuilds.
COPY pom.xml .
RUN mvn dependency:go-offline -B -q

COPY src ./src
RUN mvn clean package -DskipTests -q

# Stage 2: runtime
# JRE-only Alpine image — ~70 MB vs ~500 MB for the full JDK image.
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Non-root user — container processes must not run as root
RUN addgroup -S jshell && adduser -S jshell -G jshell

COPY --from=build /app/target/j-shell-*.jar app.jar

# Workspace directory for file operations — owned by the app user
RUN mkdir -p /app/workspace && chown -R jshell:jshell /app

USER jshell

ENV JAVA_OPTS="-Xmx512m -Xms128m -XX:+UseSerialGC"

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD pgrep -f "app.jar" > /dev/null || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
