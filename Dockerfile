# One image recipe for all services: docker build --build-arg MODULE=ledger-service .
FROM eclipse-temurin:21-jdk AS build
ARG MODULE
WORKDIR /src
COPY . .
RUN chmod +x mvnw && ./mvnw -B -q -pl ${MODULE} -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
ARG MODULE
WORKDIR /app
COPY --from=build /src/${MODULE}/target/${MODULE}-*-SNAPSHOT.jar app.jar
RUN adduser -D app && chown app /app
USER app
ENTRYPOINT ["java", "-jar", "app.jar"]
