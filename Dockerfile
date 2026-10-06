FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY domain domain
COPY simulator simulator
COPY server server
COPY config config
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp package
FROM eclipse-temurin:21-jre AS server
WORKDIR /app
COPY --from=build /build/server/target/server-1.0.0.jar app.jar
COPY config /app/config
ENV GAME_CONFIG_DIR=/app/config
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
FROM eclipse-temurin:21-jre AS simulator
WORKDIR /app
COPY --from=build /build/simulator/target/simulator-1.0.0-jar-with-dependencies.jar simulator.jar
COPY config /app/config
ENTRYPOINT ["java","-Xmx1g","-jar","simulator.jar"]
CMD ["/app/config","1000","/app/reports"]
