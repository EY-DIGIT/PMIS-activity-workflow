# ---- build stage --------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# cache dependencies first
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src src
RUN mvn -B -q -DskipTests package

# ---- runtime stage ------------------------------------------------
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# non-root user
RUN addgroup -S app && adduser -S app -G app
USER app

COPY --from=build /workspace/target/*.jar /app/app.jar

EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
