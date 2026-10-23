FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --create-home --uid 10001 app
WORKDIR /app
COPY --from=build /build/target/atsidek-*.jar app.jar
RUN mkdir -p /app/data && chown app:app /app/data
USER app
VOLUME /app/data
EXPOSE 8080
ENV JAVA_OPTS="-Xmx256m"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
