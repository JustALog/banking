# One image recipe for every service:  docker build --build-arg MODULE=ledger-service -t banking/ledger-service:dev .
FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -pl ${MODULE} -am -DskipTests package \
 && cp ${MODULE}/target/${MODULE}-*.jar /app.jar

FROM eclipse-temurin:21-jre
COPY --from=build /app.jar /app/app.jar
USER 65532
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
