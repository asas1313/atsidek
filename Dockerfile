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
ARG APP_VERSION=1.0.0
ARG GIT_SHA=local
ENV ATSIDEK_VERSION=$APP_VERSION ATSIDEK_BUILD=$GIT_SHA
ENV JAVA_OPTS="-Xmx256m"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
