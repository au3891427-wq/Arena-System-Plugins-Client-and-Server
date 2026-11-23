FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Djava.awt.headless=true"
COPY --from=build /workspace/target/arenasystem-telegram-bot.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java --add-modules jdk.httpserver $JAVA_OPTS -jar /app/app.jar"]
