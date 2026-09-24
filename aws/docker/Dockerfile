# One Dockerfile for all five services.
#
# The services are independent Maven projects with identical build shapes, so a
# single parameterised file is honest about that rather than five near-copies
# that drift apart:
#
#   docker build -f aws/docker/Dockerfile --build-arg SERVICE=routing-service \
#                --build-arg PORT=7023 -t trafficflow/routing-service .
#
# The build context is the repository root, not this folder, because the build
# needs the service directory.

# ---------------------------------------------------------------- build stage
FROM maven:3.9-eclipse-temurin-21 AS build

ARG SERVICE
WORKDIR /build

# The pom is copied on its own first so that the dependency download lands in
# its own layer. Editing a Java file then rebuilds in seconds instead of
# re-fetching every jar.
COPY ${SERVICE}/pom.xml ./pom.xml
RUN mvn -B -q dependency:go-offline

COPY ${SERVICE}/src ./src

# Tests run in CI against the whole repo, not inside the image build, so a
# broken test fails the pipeline where the output is readable rather than
# halfway through a docker build.
RUN mvn -B -q package -DskipTests \
    && cp "target/${SERVICE}.jar" /build/app.jar

# -------------------------------------------------------------- runtime stage
FROM eclipse-temurin:21-jre-alpine

ARG SERVICE
ARG PORT
ENV SERVICE_NAME=${SERVICE}

# A JRE, not a JDK: the compiler has no business being in the running image.
# Non-root because nothing in these services needs root, and the default is the
# one people forget to change.
RUN addgroup -S trafficflow && adduser -S trafficflow -G trafficflow

WORKDIR /app
COPY --from=build /build/app.jar ./app.jar
RUN chown -R trafficflow:trafficflow /app

USER trafficflow
EXPOSE ${PORT}

# Container memory is not the same as heap. Letting the JVM read the cgroup
# limit is what stops a Fargate task from being killed for using more than it
# was given.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
