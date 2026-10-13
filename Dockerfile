FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace

COPY pom.xml pom.xml
COPY src src

RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

RUN addgroup -S app && adduser -S -G app app
COPY --from=build --chown=app:app /workspace/target/smart-blood-bank-1.0.0.jar /app/app.jar

USER app
EXPOSE 3000

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
